package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 创建线程池
     */
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        String groupName = "g1";
        String consumerName = "c1";

        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取队列中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2L)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 3. 解析消息中的订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    // 4. 下单
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, groupName, record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        /**
         * 处理出现异常的消息
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 获取pendingList中的订单信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(groupName, consumerName),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    // 2. 判断消息获取是否成功
                    if (list == null || list.isEmpty()) {
                        // 如果获取失败，说明pendingList中没有消息，结束循环
                        break;
                    }
                    // 3. 解析消息中的订单
                    MapRecord<String, Object, Object> record = list.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    // 4. 下单
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName, groupName, record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 获取用户
        Long userId = voucherOrder.getUserId();
        // 2. 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3. 获取锁
        boolean isLock = lock.tryLock();
        // 4. 判断是否获取锁成功
        if (!isLock) {
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取代理对象（事务）
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取userId和orderId
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1. 执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 2. 判断结果是否为0
        // 2.1 不为0，没有购买资格
        assert result != null;
        int r = result.intValue();
        if (r != 0) {
            // 2.2 不为0，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 3. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 4. 返回订单ID
        return Result.ok(orderId);
    }

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5. 一人一单
        Long userId = voucherOrder.getUserId();
        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }
        // 6. 扣减库存
        boolean success = seckillVoucherService
                .update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }
        // 7. 创建订单
        save(voucherOrder);
    }
}
