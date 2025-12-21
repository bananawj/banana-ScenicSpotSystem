package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Object queryById(Long id) {
        //1.首先从redis中查询数据
        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+ id);
        //2.判断数据是否存在
        if (StrUtil.isNotBlank(s)){
            Shop shop = JSONUtil.toBean(s, Shop.class);
            return Result.ok(shop);
        }
        //判断字符串是否是空值
        if (s != null){  //当字符串不是null时，就是空值""
            //返回一个错误信息
            return Result.ok("店铺信息不存在");
        }


        //3.不存在，根据id查询数据库
        Shop byId = getById(id);
        //4.存在，写入redis
        if (byId != null){
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(byId),30L, TimeUnit.MINUTES);
            return  Result.ok(byId);
        }

        //缓存穿透--将空值写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,"",2L, TimeUnit.MINUTES);


        //5.不存在，返回错误信息
        return Result.fail("店铺不存在");
    }


    /**
     * 修改商铺信息
     * @param shop 商铺数据
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null){
            Result.ok("店铺id不能为空");
        }
        //更新数据库
        updateById( shop);
        //删除缓存-redis
        log.info("删除缓存--redis店铺信息（修改店铺信息）");
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }
}
