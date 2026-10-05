package com.hmdp.aop;

import com.hmdp.exception.LockFailureException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.Expression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.lang.reflect.Method;

/**
 * 分布式锁切面：拦截@Lock注解方法，基于Redisson可重入锁实现
 * <p>
 * SpEL模板解析锁key（如"order:#{#voucherOrder.userId}"），
 * waitTime内未获取到锁抛出LockFailureException；
 * leaseTime=-1时启用看门狗自动续期，避免业务未完成锁提前释放
 */
@Aspect
@Component
@Order(0) // 保证锁切面优先于事务切面执行：先获取锁再开启事务，防止锁释放后事务未提交的并发窗口
public class LockAspect {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final TemplateParserContext TEMPLATE_CONTEXT = new TemplateParserContext();

    @Resource
    private RedissonClient redissonClient;

    @Around("@annotation(lock)")
    public Object around(ProceedingJoinPoint pjp, Lock lock) throws Throwable {
        // 1.解析锁名称（支持SpEL模板动态指定）
        String name = parseLockName(lock, pjp);
        // 2.获取可重入锁并尝试加锁
        RLock rLock = redissonClient.getLock(name);
        boolean isLock = rLock.tryLock(lock.waitTime(), lock.leaseTime(), lock.unit());
        if (!isLock) {
            throw new LockFailureException("获取锁失败：" + name);
        }
        try {
            // 3.执行业务
            return pjp.proceed();
        } finally {
            // 4.释放锁（仅释放当前线程持有的锁）
            if (rLock.isHeldByCurrentThread()) {
                rLock.unlock();
            }
        }
    }

    /**
     * 解析锁名称：包含#{}时按SpEL模板解析，否则按字面量
     */
    private String parseLockName(Lock lock, ProceedingJoinPoint pjp) {
        String name = lock.name();
        if (!name.contains("#{")) {
            return name;
        }
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        MethodBasedEvaluationContext context =
                new MethodBasedEvaluationContext(null, method, pjp.getArgs(), DISCOVERER);
        Expression expression = PARSER.parseExpression(name, TEMPLATE_CONTEXT);
        return expression.getValue(context, String.class);
    }
}
