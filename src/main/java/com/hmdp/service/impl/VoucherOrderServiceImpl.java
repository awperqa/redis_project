package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

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

    //    @Autowired
//    private IVoucherService iVoucherService;
    @Autowired
    private ISeckillVoucherService iSeckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private IVoucherOrderService iVoucherOrderService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private VoucherOrderServiceImpl voucherOrderService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

     @PostConstruct
     private void init(){
         SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
     }


    private  class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true){
                try {
                    //获得队列中的订单消息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            RLock lock = redissonClient.getLock("lock:order:" + userId);
            boolean tryLock = lock.tryLock();
            if(!tryLock){
                log.error("请勿重复下单");
                return;
            }
            voucherOrderService.createVoucherOrder(voucherOrder);
            lock.unlock();
        }
    }



    @Override
    public Result seckillVoucher(Long voucherId) {
//        //查询优惠价
//        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        LocalDateTime beginTime = voucher.getBeginTime();
//        LocalDateTime endTime = voucher.getEndTime();
//        if (beginTime.isAfter(LocalDateTime.now())) {
//            //未开始
//            return Result.fail("抢购未开始");
//        }
//        //判断秒杀是否结束
//        if (endTime.isBefore(LocalDateTime.now())) {
//            //已结束
//            return Result.fail("抢购已结束");
//        }
//        //判断库存是否足够
//        Integer stock = voucher.getStock();
//        if (stock < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //获取分布式锁对象
////        SimpleRedisLock redisLock = new SimpleRedisLock("order" + userId, redisTemplate);
//        RLock redisLock = redissonClient.getLock("lock:order" + userId);
//        Boolean tryLock = redisLock.tryLock();
//        //判断锁获取是否成功
//        if(!tryLock){
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            return iVoucherOrderService.createVoucherOrder(voucherId);
//        }finally {
//            redisLock.unlock();
//        }
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long result = (Long) redisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        //判断结果是否为0
        if(result!=0){
            //不为0,无法进行购买
            return Result.fail(result == 1 ? "库存不足" :"请勿重复购买");
        }
        //为0，放订单信息放入阻塞队列中
        long orderId = redisIdWorker.nextId("order:");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        //返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            return ;
        }
        //扣减库存
        boolean update = iSeckillVoucherService.update().setSql("stock = stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if(!update){
            return ;
        }
        //创建订单
        long id = redisIdWorker.nextId("order");
        voucherOrder.setVoucherId(voucherOrder.getVoucherId());
        voucherOrder.setId(id);
        voucherOrder.setUserId(userId);
        save(voucherOrder);
    }
}
