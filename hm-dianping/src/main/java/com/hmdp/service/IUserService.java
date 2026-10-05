package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);

    void logout(String token);

    /**
     * 用户签到（BitMap，按月记录）
     * @return 无
     */
    Result sign();

    /**
     * 统计当前用户截止今天在本月的连续签到天数
     * @return 连续签到天数
     */
    Result signCount();
}