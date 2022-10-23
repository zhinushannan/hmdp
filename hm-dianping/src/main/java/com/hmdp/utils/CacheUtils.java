package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;

/**
 * @author zhinushannan
 */
@Slf4j
@Component
public class CacheUtils {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 设置过期缓存
     *
     * @param key   缓存的键
     * @param value 缓存的值
     * @param time  缓存的时间
     * @param unit  缓存的时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }


    /**
     * 使用空值解决缓存穿透
     *
     * @param keyPrefix  缓存key的前缀
     * @param id         缓存key的id
     * @param type       缓存的类型
     * @param dbFallback 回调函数：根据id查询数据库的函数
     * @param time       缓存过期时间
     * @param unit       缓存过期时间单位
     * @param <R>        对象类型
     * @param <ID>       ID的类型
     * @return 返回查询结果，若命中缓存或数据库则返回对应的对象，若没有命中则返回空
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从redis查询
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 若不存在，则判断命中的是否是空值
        if (json != null) {
            // 返回错误信息
            return null;
        }
        // 4. 不存在，根据id查库
        R r = dbFallback.apply(id);
        // 5. 不存在，返回错误
        if (null == r) {
            // 向redis写入空值
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6. 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time, unit);
        return r;
    }

    /**
     * 使用线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 利用互斥锁解决缓存击穿的逻辑
     *
     * @param id shop的id
     * @return 返回shop对象或null
     */
    public <R, ID> R queryWithMutex(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3. 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回错误信息
            return null;
        }

        // 4. 实现缓存重建
        String lockKey = lockKeyPrefix + id;
        R r;
        try {
            // 4.1 获取互斥锁
            boolean isLock = this.lock(lockKey);
            // 4.2 判断是否获取成功
            if (!isLock) {
                Thread.sleep(50);
                queryWithMutex(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4 获取锁成功
            // 4.4.1 判断缓存中是否存在
            json = stringRedisTemplate.opsForValue().get(key);
            // 4.4.2 若缓存不存在，则根据id查库
            if (StrUtil.isNotBlank(json)) {
                r = JSONUtil.toBean(json, type);
            } else {
                r = dbFallback.apply(id);
            }
            // 5. 不存在，返回错误
            if (null == r) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6. 存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            this.unlock(lockKey);
        }
        // 7. 返回
        return r;
    }


    /**
     * 逻辑过期解决缓存击穿
     *
     * @param id id
     * @return 返回对象
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3. 不存在直接返回null
            return null;
        }
        // 4. 命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 5.1 未过期，直接返回
            return r;
        } else {
            // 5.2 已过期，缓存重建
            // 6. 缓存重建
            // 6.1 获取互斥锁
            String lockKey = lockKeyPrefix + id;
            boolean isLock = lock(lockKey);
            if (isLock) {
                // 6.2 再次检验是否被更新
                if (!expireTime.isAfter(LocalDateTime.now())) {
                    // 6.3 获取锁成功且缓存依旧不存在，开启独立线程，实现缓存重建
                    CACHE_REBUILD_EXECUTOR.submit(() -> {
                        try {
                            R r1 = dbFallback.apply(id);
                            this.setWithLogicalExpire(key, r1, time, unit);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            unlock(lockKey);
                        }
                    });
                }
            }
        }
        // 6.4 返回过期的商铺信息
        return r;
    }

    /**
     * 设置逻辑过期缓存
     *
     * @param key   缓存的键
     * @param value 缓存的值
     * @param time  缓存的有效时间
     * @param unit  缓存的有效时间单位
     */
    private void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 获取锁，使用若不存在则添加的方式向redis中添加锁
     *
     * @param key 键
     * @return 若成功添加，即redis中不存在该键，则返回true
     */
    private boolean lock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
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


}
