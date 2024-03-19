package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;
    @Autowired
    private RedisTemplate redisTemplate;

    public SimpleRedisLock(String name, RedisTemplate redisTemplate) {
        this.name = name;
        this.redisTemplate = redisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";

    //获取分布式锁
    @Override
    public Boolean tryLock(Long timeoutSec) {
        //线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId , timeoutSec, TimeUnit.SECONDS);
        return success;
    }

    @Override
    public void unlock() {
        //判断锁是否一致
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        String id = (String) redisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(threadId.equals(id)){
            redisTemplate.delete(KEY_PREFIX+name);
        }
    }

}
