package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.aop.Lock;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 秒杀资格判断的lua脚本（库存判断 + 一人一单判断 + 扣库存，原子执行）
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.获取用户
        Long userId = UserHolder.getUser().getId();
        // 2.生成订单id
        long orderId = redisIdWorker.nextId("order");
        // 3.执行lua脚本，原子完成：库存判断、一人一单判断、扣库存
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        // 4.判断结果是否为0
        if (r != 0) {
            // 4.1.不为0，代表没有购买资格：1库存不足，2重复下单
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 5.有购买资格，订单消息发送到Kafka（key为voucherId，同券消息进同一分区保证顺序），消费者异步落库
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        try {
            // 同步等待acks=all确认（含重试），确保消息可靠落盘后才返回成功
            kafkaTemplate.send(RedisConstants.KAFKA_ORDER_TOPIC, voucherId.toString(), JSONUtil.toJsonStr(voucherOrder))
                    .get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Kafka消息发送失败，回滚Redis预扣：orderId={}", orderId, e);
            // 6.发送失败补偿：回滚Redis预扣的库存和下单记录，避免少卖
            stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + voucherId);
            stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
            return Result.fail("下单失败，请重试");
        }
        return Result.ok(orderId);
    }

    @Override
    @Lock(name = "order:#{#voucherOrder.userId}") // Redisson可重入锁+看门狗续期，锁在事务外层
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 1.一人一单兜底校验（防止Redis与数据库不一致）
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户{}已经购买过一次！", userId);
            return;
        }
        // 2.扣减库存（乐观锁：stock > 0 才扣减，解决超卖）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) // where voucher_id = ? and stock > 0
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }
        // 3.保存订单（联合唯一索引兜底：并发重复插入抛DuplicateKeyException由消费者幂等处理）
        save(voucherOrder);
    }
}
