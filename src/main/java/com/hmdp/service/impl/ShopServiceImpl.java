package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
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
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //redis无数据->查询数据库
        Shop shop = getById(id);
        //判断店铺是否存在
        if(shop==null){
            //不存在返回404
            return Result.fail("404");
        }
        //存在则缓存数据到redis
        String toJsonStr = JSONUtil.toJsonStr(shop);
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,toJsonStr);
        //返回数据
        return Result.ok(shop);
    }
}
