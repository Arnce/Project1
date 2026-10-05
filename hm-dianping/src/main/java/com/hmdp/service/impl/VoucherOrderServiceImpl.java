package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.aop.Lock;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.LockFailureException;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.STREAM_ORDER_KEY;

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

    /**
     * 自注入代理对象：消费线程中调用createVoucherOrder时保证@Transactional与@Lock生效
     */
    @Resource
    @Lazy
    private IVoucherOrderService self;

    /**
     * 秒杀资格判断的lua脚本（库存判断 + 一人一单判断 + 扣库存 + 订单消息XADD入stream，原子执行）
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 消费组与消费者名称
     */
    private static final String GROUP_NAME = "g1";
    private static final String CONSUMER_NAME = "c1";

    /**
     * 异步下单消费线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 类初始化完成后，创建消费组并启动消费线程
     */
    @PostConstruct
    private void init() {
        // 1.创建消费组（stream不存在时自动创建；已存在则忽略BUSYGROUP异常）
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_ORDER_KEY, GROUP_NAME);
        } catch (Exception e) {
            log.info("消费组已存在：{}", e.getMessage());
        }
        // 2.先处理上次运行遗留的pending-list消息（防止JVM重启导致订单丢失）
        SECKILL_ORDER_EXECUTOR.submit(this::handlePendingList);
        // 3.启动常驻消费线程
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 消费线程：阻塞读取stream.orders中的新订单消息并处理（替代JVM内存阻塞队列，消息持久化于Redis）
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.读取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(GROUP_NAME, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_ORDER_KEY, ReadOffset.lastConsumed())
                    );
                    // 2.判空
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    // 3.处理订单
                    handleRecord(list.get(0));
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 兜底处理pending-list中未确认的消息
                    handlePendingList();
                }
            }
        }
    }

    /**
     * 处理一条订单消息：解析 -> 创建订单 -> XACK确认
     */
    private void handleRecord(MapRecord<String, Object, Object> record) {
        // 1.解析消息为订单对象
        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
        try {
            // 2.通过代理对象调用，触发@Lock与@Transactional
            self.createVoucherOrder(voucherOrder);
            // 3.处理成功，确认消费 XACK
            stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDER_KEY, GROUP_NAME, record.getId());
        } catch (LockFailureException e) {
            // 4.获取锁失败=重复下单，属业务性拒绝，直接确认消息跳过
            log.warn("重复下单，跳过：{}", e.getMessage());
            stringRedisTemplate.opsForStream().acknowledge(STREAM_ORDER_KEY, GROUP_NAME, record.getId());
        }
        // 其他异常向上抛出，消息不ack留在pending-list，由handlePendingList重试
    }

    /**
     * 处理pending-list中未确认的消息（读取已读未ack的消息并重试，防止消息丢失）
     */
    private void handlePendingList() {
        while (true) {
            try {
                // 1.读取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(STREAM_ORDER_KEY, ReadOffset.from("0"))
                );
                // 2.判空：pending-list处理完毕
                if (list == null || list.isEmpty()) {
                    break;
                }
                // 3.处理消息
                handleRecord(list.get(0));
            } catch (Exception e) {
                log.error("处理pending-list订单异常", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.获取用户
        Long userId = UserHolder.getUser().getId();
        // 2.生成订单id
        long orderId = redisIdWorker.nextId("order");
        // 3.执行lua脚本，原子完成：库存判断、一人一单判断、扣库存，并将订单消息XADD到stream.orders
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 4.判断结果是否为0
        if (r != 0) {
            // 4.1.不为0，代表没有购买资格：1库存不足，2重复下单
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 5.有购买资格，订单消息已由lua写入stream.orders（Redis持久化），消费线程异步落库
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
        // 3.保存订单
        save(voucherOrder);
    }
}
