package com.hmdp.config;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final RedisUtils redisUtils;

    public RefreshTokenInterceptor(RedisUtils redisUtils) {
        this.redisUtils = redisUtils;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            //没有token，直接放行，后续拦截器决定是否拦截
            return true;
        }

        //查询Redis的用户
        Map<Object, Object> userMap = redisUtils.hGetAll(RedisConstants.LOGIN_USER_KEY + token);
        if (userMap.isEmpty()) {
            //token无效，放行，后续拦截器决定是否拦截
            return true;
        }

        //转为UserDTO保存到ThreadLocal
        UserDTO userDTO = new UserDTO(
                Long.valueOf(userMap.get("id").toString()),
                userMap.get("nickName").toString(),
                userMap.get("icon").toString()
        );
        UserHolder.saveUser(userDTO);

        //刷新token有效期
        redisUtils.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}