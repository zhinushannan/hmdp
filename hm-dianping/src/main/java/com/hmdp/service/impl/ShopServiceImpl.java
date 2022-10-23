package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
//        Shop shop = queryWithPassThrough(id);
        Shop shop = queryWithMutex(id);
        // 7. 返回
        return Result.ok(shop);
    }

    /**
     * 利用互斥锁解决缓存击穿的逻辑
     *
     * @param id shop的id
     * @return 返回shop对象或null
     */
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            // 返回错误信息
            return null;
        }

        // 4. 实现缓存重建
        String lockKey = "lock:shop:" + id;
        Shop shop;
        try {
            // 4.1 获取互斥锁
            boolean isLock = this.lock(lockKey);
            // 4.2 判断是否获取成功
            if (!isLock) {
                Thread.sleep(50);
                queryWithMutex(id);
            }
            // 4.4 获取锁成功
            // 4.4.1 判断缓存中是否存在
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 4.4.2 若缓存不存在，则根据id查库
            if (StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
            } else {
                shop = getById(id);
            }
            // 5. 不存在，返回错误
            if (null == shop) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6. 存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            this.unlock(lockKey);
        }
        // 7. 返回
        return shop;
    }

    /**
     * 获取锁，使用若不存在则添加的方式向redis中添加锁
     *
     * @param key 键
     * @return 若成功添加，即redis中不存在该键，则返回true
     */
    private boolean lock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁，删除键
     *
     * @param key 键
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 解决缓存穿透的逻辑
     *
     * @param id shop的id
     * @return 返回shop对象或null
     */
    private Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中的是否是空值
        if (shopJson != null) {
            // 返回错误信息
            return null;
        }

        // 4. 不存在，根据id查询数据库
        Shop shop = getById(id);
        // 5. 不存在，返回错误
        if (null == shop) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 7. 返回
        return shop;
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (null == id) {
            return Result.fail("店铺ID不能为空！");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
