package com.hmdp.utils;


import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


public class BloomFilter {

    private static final Config config = new Config();
    static {
        config.useSingleServer().setAddress("redis://localhost:6379");
    }
    private static final RBloomFilter<Long> bloomFilter = Redisson.create(config).getBloomFilter("shop:");

    private BloomFilter(){}

    public static RBloomFilter<Long> getBloomFilter() {
        bloomFilter.tryInit(1000, 0.01);
        return bloomFilter;
    }
}
