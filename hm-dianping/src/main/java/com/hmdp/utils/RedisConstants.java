package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;


    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop-type:";
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** Kafka下单主题（key为voucherId，同券消息进同一分区保证顺序） */
    public static final String KAFKA_ORDER_TOPIC = "voucher.order";
    /** 下单死信主题（消费重试3次仍失败的消息） */
    public static final String KAFKA_ORDER_DLQ_TOPIC = "voucher.order.dlq";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}