package com.hmdp.utils;

/**
 * @author zhinushannan
 */
public interface ILock {

    /**
     * 尝试获取锁
     *
     * @param timeoutSec 如果成功获取锁，锁的存活时间
     * @return true代表获取成功，false代表获取失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();

}
