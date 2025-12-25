package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static cn.hutool.http.ContentType.JSON;
import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private CacheClient cacheClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }

    //定义线程池
    private final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        //1.首先从redis中查询数据
        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断数据是否存在
        if (StrUtil.isBlank(s)) {
            return null;
        }

        //4.命中，需要先把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(s, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = BeanUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是逻辑时间否为过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1.未过期，直接返回店铺信息
            return shop;
        }

        //5.2.过期，需要重建缓存
        //6.缓存重构
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Boolean idLock = tryLock(lockKey);
        //6.2判断是否获取成功
        if (idLock) {
            //6.3成功，开启独立线程，实现缓存重构
            CACHE_REBUILD_POOL.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id, 30L);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        } else {
            //6.4获取互斥锁失败，返回过期的店铺信息
            return shop;
        }



        //3.不存在，根据id查询数据库
        Shop byId = getById(id);
        //4.存在，写入redis
        if (byId != null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(byId), 30L, TimeUnit.MINUTES);
            return byId;
        }

        //缓存穿透--将空值写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", 2L, TimeUnit.MINUTES);

        //5.不存在，返回错误信息
        return null;
    }

    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        //1.首先从redis中查询数据
        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断数据是否存在
        if (StrUtil.isNotBlank(s)) {
            Shop shop = JSONUtil.toBean(s, Shop.class);
            return shop;
        }
        //判断字符串是否是空值
        if (s != null) {  //当字符串不是null时，就是空值""
            //返回一个错误信息
            return null;
        }

        //@1.实现缓存重建
        //@2.获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            Boolean flag = tryLock(lockKey);
            //@4.失败则休眠并重试
            if (!flag) {
                Thread.sleep(50);
                queryWithPassThrough(id);

            }

            shop = getById(id);
            //@3.判断是否获取成功
            //不存在，返回错误信息
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", 2L, TimeUnit.MINUTES);
                return null;
            }
            //4.存在，写入redis


            //缓存穿透--将值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), 30L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        //1.首先从redis中查询数据
        String s = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断数据是否存在
        if (StrUtil.isNotBlank(s)) {
            Shop shop = JSONUtil.toBean(s, Shop.class);
            return shop;
        }
        //判断字符串是否是空值
        if (s != null) {  //当字符串不是null时，就是空值""
            //返回一个错误信息
            return null;
        }


        //3.不存在，根据id查询数据库
        Shop byId = getById(id);
        //4.存在，写入redis
        if (byId != null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(byId), 30L, TimeUnit.MINUTES);
            return byId;
        }

        //缓存穿透--将空值写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", 2L, TimeUnit.MINUTES);


        //5.不存在，返回错误信息
        return null;
    }


    /**
     * 修改商铺信息
     *
     * @param shop 商铺数据
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        if (shop.getId() == null) {
            Result.ok("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存-redis
        log.info("删除缓存--redis店铺信息（修改店铺信息）");
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }


    //获取锁
    public Boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    public Boolean unLock(String key) {
        return stringRedisTemplate.delete(key);
    }


    public void saveShop2Redis(Long id, Long expireSeconds) {
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


}
