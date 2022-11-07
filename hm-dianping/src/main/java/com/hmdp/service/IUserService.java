package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 根据手机号发送短信，并保存至session中
     *
     * @param phone   手机号
     * @return 返回失败或成功
     */
    Result sendCode(String phone);

    /**
     * 登录功能
     *
     * @param loginForm 登录表单
     * @return 返回失败或成功
     */
    Result login(LoginFormDTO loginForm);
}
