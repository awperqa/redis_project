package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public Result queryById(Long id) {
        //查询redis
        String shopJson = (String) redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //redis有数据直接返回
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        if (shopJson != null) {
            return Result.fail("店铺不存在!");
        }
        //通过互斥锁避免缓存击穿
        //获取互斥锁
        String key = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            Boolean lock = tryLock(key);
            //判断锁是否创建成功
            if (!lock) {
                //有锁休眠重试
                Thread.sleep(50);
                return queryById(id);
            }
            //成功
            //进行二次检查 redis缓存
            String check = (String) redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
            //redis有数据直接返回
            if (StrUtil.isNotBlank(check)) {
                Shop shopBean = JSONUtil.toBean(check, Shop.class);
                return Result.ok(shopBean);
            }
            if (check != null) {
                return Result.fail("店铺不存在!");
            }
            //查询数据库
            shop = getById(id);
            //判断店铺是否存在
            if (shop == null) {
                //不存在返回404
                redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
            }
            //存在则缓存数据到redis
            String toJsonStr = JSONUtil.toJsonStr(shop);
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, toJsonStr, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException();
        } finally {
            //释放互斥锁
            unLock(key);
        }
        //返回数据
        return Result.ok(shop);
    }


    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("店铺id不能为空");
        }
        //修改数据库数据
        updateById(shop);
        //清理redis缓存
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    private static final ExecutorService CACHE_REBUILD = Executors.newFixedThreadPool(10);


    //逻辑过期处理缓存击穿
    @Override
    public Shop overDoCache(Long id) {
        //查询redis是否有数据
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = (String) redisTemplate.opsForValue().get(key);
        //无数据直接返回null
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //有数据
        LocalDateTime expireTime = redisData.getExpireTime();
        LocalDateTime now = LocalDateTime.now();
        //判断缓存是否过期
        //未过期直接返回
        if (expireTime.isAfter(now)) {
            return shop;
        }
        //过期->获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //判断互斥锁是否创建成功
        Boolean lock = tryLock(lockKey);
        //未成功返回旧数据
        if (!lock) {
            return shop;
        }
        //成功先进行二次redis核验
        String shopCheck = (String) redisTemplate.opsForValue().get(key);
        RedisData redisDataCheck = JSONUtil.toBean(shopCheck, RedisData.class);
        if (redisDataCheck.getExpireTime().isAfter(now)) {
            return JSONUtil.toBean((JSONObject) redisDataCheck.getData(), Shop.class);
        }
        //成功 开启独立线程更新缓存
        CACHE_REBUILD.submit(()->{
            try {
                queryWithExpire(id,20l);
                unLock(lockKey);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        return shop;


}


    private Boolean tryLock(String key) {
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return flag;
    }

    private void unLock(String key) {
        redisTemplate.delete(key);
    }

    //新增热点数据
    public void queryWithExpire(Long id, Long expire) throws InterruptedException {
        Thread.sleep(200);
        //查询店铺信息
        Shop shop = getById(id);
        //封装过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expire));
        //添加到redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
