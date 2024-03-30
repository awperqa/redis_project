package com.hmdp.config;

import cn.hutool.bloomfilter.BitMapBloomFilter;
import cn.hutool.bloomfilter.BloomFilter;
import cn.hutool.bloomfilter.BloomFilterUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@Slf4j
public class RedisConfig {

    //创建redis Bean对象
    @Bean
    public RedisTemplate redisTemplate(RedisConnectionFactory redisConnectionFactory){
        log.info("开始创建redis模板对象");
        RedisTemplate redisTemplate = new RedisTemplate();
        //设置连接工厂对象
        redisTemplate.setConnectionFactory(redisConnectionFactory);
        //设置redis key的序列化器
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        return redisTemplate;
    }


//    public static void main(String[] args) {
//        //创建布隆过滤
//        BitMapBloomFilter bitMapBloomFilter = new BitMapBloomFilter(1000);
//        for (int i = 0; i < 5000000; i++) {
//            bitMapBloomFilter.add(""+i);
//        }
//        int fainCount=0;
//        for (int i = 5000000; i < 60000000; i++) {
//            if(bitMapBloomFilter.contains(""+i)){
//                System.out.println(i+"存在");
//                fainCount++;
//            }else {
//                //System.out.println(i+"不存在");
//            }
//        }
//        System.out.println("误判："+fainCount);
//
//    }
}
