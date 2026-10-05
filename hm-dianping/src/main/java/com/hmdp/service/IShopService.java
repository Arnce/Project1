package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result updateShop(Shop shop);

    /**
     * 根据商铺类型分页查询商铺信息（支持按地理位置查询附近商户）
     * @param typeId 商铺类型id
     * @param current 页码
     * @param x 经度（为null时按普通分页查询）
     * @param y 纬度（为null时按普通分页查询）
     * @return 商铺列表（按距离排序，带distance字段）
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}