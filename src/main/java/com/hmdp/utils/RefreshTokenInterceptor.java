package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 *  * @description: 登录拦截器
 */

@RequiredArgsConstructor
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        //2.基于Token获取redis中的用户信息--这里的entries会自动判断，如果为null会返回一个空Map
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries("login:token:"+token);
        //3.判断用户是否存在
        if (entries.isEmpty()){

            return true;
        }
        //4.将对象转换为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(entries, new UserDTO(), false);
        //4.存在则保存用户信息到Thread Local
        UserHolder.saveUser(userDTO);
        //刷新过期时间TTL
        stringRedisTemplate.expire("login:token:"+token,30, TimeUnit.MINUTES);
        return true;
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
