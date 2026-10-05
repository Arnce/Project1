package com.hmdp.exception;

/**
 * 获取分布式锁失败异常
 */
public class LockFailureException extends RuntimeException {

    public LockFailureException(String message) {
        super(message);
    }
}
