package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private RedisUtils redisUtils;

    @Override
    public List<ShopType> queryTypeList() {
        //从redis查询店铺类型
        String shopTypeJson = redisUtils.get(CACHE_SHOP_TYPE_KEY + "list");

        //判断是否存在
        if(StrUtil.isNotEmpty(shopTypeJson)){
            //存在，直接返回
            return JSONUtil.toList(shopTypeJson, ShopType.class);
        }

        //不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //数据库存在，写入redis
        redisUtils.set(CACHE_SHOP_TYPE_KEY, typeList, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return typeList;
    }
}