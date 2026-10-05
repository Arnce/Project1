package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisUtils;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private RedisUtils redisUtils;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        Shop shop = queryWithMutex(id);
        return null == shop ? Result.fail("店铺不存在") : Result.ok(shop);
    }

    /**
     * 基于互斥锁解决缓存击穿：缓存未命中时，抢锁重建缓存
     */
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            // 1. 先查缓存（null=未缓存待重建；""=空值命中，店铺不存在；非空=店铺数据）
            String shopJson = redisUtils.get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                return redisUtils.get(key, Shop.class);
            }
            if (shopJson != null) {
                return null;
            }
            // 2. 缓存未命中，尝试获取互斥锁
            if (tryLock(lockKey)) {
                // 3. 获取锁成功后，双重检查缓存，防止已有线程重建好
                shopJson = redisUtils.get(key);
                if (StrUtil.isNotBlank(shopJson)) {
                    return redisUtils.get(key, Shop.class);
                }
                if (shopJson != null) {
                    return null;
                }
                // 4. 查询数据库
                shop = getById(id);
                if (shop != null) {
                    // 存在，回填缓存
                    redisUtils.set(key, shop, CACHE_SHOP_TTL, TimeUnit.MINUTES);
                } else {
                    // 不存在，缓存空值防止穿透
                    redisUtils.set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                }
            } else {
                // 5. 获取锁失败，说明有其他线程正在重建缓存，休眠后重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("缓存重建被中断", e);
        } finally {
            // 6. 释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    /**
     * 利用 setnx（setIfAbsent）原子抢占互斥锁
     */
    private boolean tryLock(String lockKey) {
        return redisUtils.setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
    }

    /**
     * 释放互斥锁
     */
    private void unLock(String lockKey) {
        redisUtils.delete(lockKey);
    }

    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        redisUtils.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis GEO、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        // 3.0.GEO数据不存在时，从数据库加载并预热到Redis（首次访问自动导入，GEOADD幂等可重复执行）
        if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
            List<Shop> shops = query().eq("type_id", typeId).list();
            if (shops.isEmpty()) {
                return Result.ok(Collections.emptyList());
            }
            Map<String, Point> geoMap = new HashMap<>(shops.size());
            for (Shop shop : shops) {
                if (shop.getX() != null && shop.getY() != null) {
                    geoMap.put(shop.getId().toString(), new Point(shop.getX(), shop.getY()));
                }
            }
            if (!geoMap.isEmpty()) {
                stringRedisTemplate.opsForGeo().add(key, geoMap);
            }
        }
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 5000 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop，并按距离顺序排序
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
