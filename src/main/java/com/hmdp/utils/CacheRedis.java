package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public class CacheRedis {
    @Autowired  
    private RedisTemplate redisTemplate;

    public CacheRedis(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithExpire(String key, Object value, LocalDateTime ExpireTime) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(ExpireTime);
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    public <R,ID> R cacheBlank(String keyPrefix,ID id,Class<R> type, Function<ID,R> dbFallBack,Long time,TimeUnit timeUnit) {
        //查询redis
        String key = keyPrefix + id;
        String Json = (String) redisTemplate.opsForValue().get(key);
        //redis有数据直接返回
        if (StrUtil.isNotBlank(Json)) {
            R r = JSONUtil.toBean(Json, type);
            return r;
        }
        if (Json != null) {
            return null;
        }
        //查询数据库
        R r = dbFallBack.apply(id);
        //判断店铺是否存在
        if (r == null) {
            //不存在返回404
            redisTemplate.opsForValue().set(key, "", time, timeUnit);
            return null;
        }
        //存在则缓存数据到redis
        String toJsonStr = JSONUtil.toJsonStr(r);
        this.set(key,toJsonStr,time,timeUnit);

        //返回数据
        return r;
    }

    private static final ExecutorService CACHE_REBUILD = Executors.newFixedThreadPool(10);

    //逻辑过期处理缓存击穿
    public <R,ID> R CacheExpire(String keyPreFix,ID id,Class<R> type,Function<ID,R> dbFailBack,LocalDateTime newExpireTime) {
        //查询redis是否有数据
        String key =keyPreFix + id;
        String Json = (String) redisTemplate.opsForValue().get(key);
        //无数据直接返回null
        if (StrUtil.isBlank(Json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //有数据
        LocalDateTime expireTime = redisData.getExpireTime();
        LocalDateTime now = LocalDateTime.now();
        //判断缓存是否过期
        //未过期直接返回
        if (expireTime.isAfter(now)) {
            return r;
        }
        //过期->获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        //判断互斥锁是否创建成功
        Boolean lock = tryLock(lockKey);
        //未成功返回旧数据
        if (!lock) {
            return r;
        }
        //成功先进行二次redis核验
        String shopCheck = (String) redisTemplate.opsForValue().get(key);
        RedisData redisDataCheck = JSONUtil.toBean(shopCheck, RedisData.class);
        if (redisDataCheck.getExpireTime().isAfter(now)) {
            return JSONUtil.toBean((JSONObject) redisDataCheck.getData(), type);
        }
        //成功 开启独立线程更新缓存
        CACHE_REBUILD.submit(()->{
            try {
                R r1 = dbFailBack.apply(id);
                this.setWithExpire(key,r1,newExpireTime);
            } catch (Exception e) {
                throw new RuntimeException();
            }finally {
                unLock(lockKey);
            }
        });
        return r;

    }


    private Boolean tryLock(String key) {
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return flag;
    }

    private void unLock(String key) {
        redisTemplate.delete(key);
    }

}


