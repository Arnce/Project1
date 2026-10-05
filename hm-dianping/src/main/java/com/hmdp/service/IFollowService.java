package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注/取消关注用户
     * @param followUserId 要关注/取关的用户id
     * @param isFollow true关注，false取关
     * @return 无
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 查询当前用户是否关注了指定用户
     * @param followUserId 指定用户id
     * @return 是否关注
     */
    Result isFollow(Long followUserId);

    /**
     * 查询当前用户与指定用户的共同关注
     * @param id 指定用户id
     * @return 共同关注的用户列表
     */
    Result followCommons(Long id);
}
