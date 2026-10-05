package com.hmdp.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁注解：声明式加锁，降低业务侵入性
 * <p>
 * name支持SpEL模板动态指定锁key，如："order:#{#voucherOrder.userId}"
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Lock {

    /**
     * 锁名称，支持SpEL模板：#{#参数名.属性}
     */
    String name();

    /**
     * 获取锁的最大等待时间（超时抛出LockFailureException）
     */
    long waitTime() default 1;

    /**
     * 锁自动释放时间，-1表示不指定，由看门狗自动续期（默认30s，每10s续一次）
     */
    long leaseTime() default -1;

    /**
     * 时间单位
     */
    TimeUnit unit() default TimeUnit.SECONDS;
}
