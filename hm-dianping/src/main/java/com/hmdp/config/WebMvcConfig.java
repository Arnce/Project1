package com.hmdp.config;

import com.hmdp.utils.RedisUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Resource
    private RedisUtils redisUtils;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //第一个拦截器：拦截一切路径，刷新token
        registry.addInterceptor(new RefreshTokenInterceptor(redisUtils))
                .addPathPatterns("/**").order(0);

        //第二个拦截器：只拦截需要登录的路径
        registry.addInterceptor(new LoginInterceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/voucher/**"
                ).order(1);
    }
}