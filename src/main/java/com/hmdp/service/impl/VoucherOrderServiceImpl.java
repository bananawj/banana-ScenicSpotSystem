package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 秒杀优惠券
     *
     * @param voucherId 优惠券id
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {//isAfter()>
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {//isBefore()<
            return Result.fail("秒杀已结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }

        //*********细节*********
        UserDTO user = UserHolder.getUser();
        synchronized (user.getId().toString().intern()) {
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
            //  createVoucherOrder(voucherId);等同于  this.createVoucherOrder(voucherId);
            //this表示拿到VoucherOrderServiceImpl这个对象
            //this指非代理对象，没有事务功能的！！！！！！！
            //所以使用动态代理对象创建代理对象
        }

    }

    @Transactional  //用动态代理对象创建代理对象，代理对象调用方法时，会执行代理对象中的方法，并调用目标对象中的方法
    public Result createVoucherOrder(Long voucherId) {

        //6.一人一单
        UserDTO user = UserHolder.getUser();
        // 6.1.锁库存--用用户ID进行加锁，大大降低了锁的范围，提升了并发性能
        /**
         * 用intern()方法，防止锁的key被垃圾回收
         * 如果创建
         * 否则每次同一个用户过来都是新的一把锁，值一样锁就一样
         */

        //6.1查询订单
        Integer count = query().eq("user_id", user.getId()).eq("voucher_id", voucherId).count();
        //6.2判断订单是否以存在
        if (count > 0) {
            return Result.fail("不能重复下单");
        }
        //6.3.扣减库存
        boolean update = seckillVoucherService.update()
                .setSql("stock=stock-1").eq("voucher_id", voucherId)
                //.eq("stock",voucher.getStock()),会导成功率太低，因为太“小心了”
                .gt("stock", 0)//乐观锁--CAS（还有版本号法）
                .update();
        if (!update) {
            return Result.fail("库存不足");
        }

        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1生成订单Id
        long order = redisIdWorker.nextId("order");
        voucherOrder.setId(order);
        //6.2获取用户Id

        voucherOrder.setUserId(user.getId());
        //6.3获取代金卷Id
        voucherOrder.setVoucherId(voucherId);
        //7.返回订单id
        save(voucherOrder);

        return Result.ok(order);
    }

}
