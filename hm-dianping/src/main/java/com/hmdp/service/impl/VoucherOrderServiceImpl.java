package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
     * 自注入代理对象：异步线程中调用createVoucherOrder时保证@Transactional事务生效
     */
    @Resource
    @Lazy
    private IVoucherOrderService self;

    /**
     * 秒杀资格判断的lua脚本（库存判断 + 一人一单判断 + 扣库存，原子执行）
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 异步处理线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 阻塞队列：解耦资格判断与下单落库
     */
    private final BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    /**
     * 类初始化完成后，开启异步线程处理阻塞队列中的订单
     */
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 异步下单线程：不断从阻塞队列中获取订单信息并落库
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建分布式锁对象（集群环境下的一人一单）
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        // 3.尝试获取锁
        boolean isLock = lock.tryLock(5);
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，说明重复下单
            log.error("不允许重复下单！userId:{}", userId);
            return;
        }
        try {
            // 5.通过代理对象调用，保证事务生效
            self.createVoucherOrder(voucherOrder);
        } finally {
            // 6.释放锁
            lock.unlock();
        }
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
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 4.判断结果是否为0
        if (r != 0) {
            // 4.1.不为0，代表没有购买资格：1库存不足，2重复下单
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 5.有购买资格，封装订单信息放入阻塞队列，异步下单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 5.1.订单id
        voucherOrder.setId(orderId);
        // 5.2.用户id
        voucherOrder.setUserId(userId);
        // 5.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        // 6.返回订单id（前端可凭此id轮询下单结果）
        return Result.ok(orderId);
    }

    @Override
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
