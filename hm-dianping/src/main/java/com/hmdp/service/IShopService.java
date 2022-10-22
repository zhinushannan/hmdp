package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 根据id查询商铺
     *
     * @param id 商铺id
     * @return 返回统一返回值
     */
    Result queryById(Long id);

    /**
     * 根据id更新商铺
     *
     * @param shop 商铺对象
     * @return 返回统一返回值
     */
    Result update(Shop shop);
}
