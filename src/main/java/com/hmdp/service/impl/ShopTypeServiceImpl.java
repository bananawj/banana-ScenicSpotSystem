package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result ListType() {
        String s = stringRedisTemplate.opsForValue().get("cache:shop:type");
        if (s != null && !s.isEmpty()) {
            try {
                return Result.ok(JSONUtil.toBean(s, List.class));
            } catch (Exception e) {
                // 如果解析失败，则从数据库重新加载数据
                stringRedisTemplate.delete("cache:shop:type");
            }
        }
        List<ShopType> sort = query().orderByAsc("sort").list();
        if (sort == null){
            return Result.ok("数据不存在");
        }
        stringRedisTemplate.opsForValue().set("cache:shop:type", JSONUtil.toJsonStr(sort));
        return Result.ok(sort);
    }
}