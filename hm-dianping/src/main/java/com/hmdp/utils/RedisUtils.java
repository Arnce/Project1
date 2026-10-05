package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class RedisUtils {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void set(String key, Object value) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public <T> T get(String key, Class<T> type) {
        String json = get(key);
        return json == null ? null : JSONUtil.toBean(json, type);
    }

    public boolean setIfAbsent(String key, Object value, Long time, TimeUnit unit) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, JSONUtil.toJsonStr(value), time, unit);
        return Boolean.TRUE.equals(flag);
    }

    public boolean setIfAbsent(String key, String value, Long time, TimeUnit unit) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, time, unit);
        return Boolean.TRUE.equals(flag);
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.delete(key));
    }

    public boolean expire(String key, Long time, TimeUnit unit) {
        return Boolean.TRUE.equals(stringRedisTemplate.expire(key, time, unit));
    }

    public boolean hasKey(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    }

    public void hSetAll(String key, Map<String, String> map) {
        stringRedisTemplate.opsForHash().putAll(key, map);
    }

    public Map<Object, Object> hGetAll(String key) {
        return stringRedisTemplate.opsForHash().entries(key);
    }
}