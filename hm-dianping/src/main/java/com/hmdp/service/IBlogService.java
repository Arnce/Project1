package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 保存探店笔记，并推送到粉丝收件箱（Feed流-推模式）
     * @param blog 探店笔记
     * @return 笔记id
     */
    Result saveBlog(Blog blog);

    /**
     * 查询热门探店笔记
     * @param current 页码
     * @return 笔记列表
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询探店笔记详情
     * @param id 笔记id
     * @return 笔记详情
     */
    Result queryBlogById(Long id);

    /**
     * 点赞/取消点赞（基于Redis的SortedSet，同一用户只能点赞一次，且可排序）
     * @param id 笔记id
     * @return 无
     */
    Result likeBlog(Long id);

    /**
     * 查询笔记的点赞排行榜（最早点赞的TOP5）
     * @param id 笔记id
     * @return 点赞用户列表
     */
    Result queryBlogLikes(Long id);

    /**
     * 查询当前用户收件箱中的笔记（Feed流-滚动分页）
     * @param max 上一次查询的最小时间戳（本次查询的最大分数）
     * @param offset 偏移量（与上次查询最小时间戳相同的条数）
     * @return 滚动分页结果
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
