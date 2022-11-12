package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀优惠券的功能
     * @param voucherId 优惠券id
     * @return 返回业务响应
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 事务管理的、创建优惠券订单的方法
     * @param voucherOrder 优惠券对象
     */
    void createVoucherOrder(VoucherOrder voucherOrder);

}
