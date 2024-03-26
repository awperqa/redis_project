package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.TimeoutUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Override
    public Result queryTypeList() {
        //查询redis
        List list = redisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        //redis有数据直接返回
        if(list!=null&&list.size()>0){
            JSONArray jsonArray = JSONUtil.parseArray(list.toString());
//            List<ShopType> typeList = JSONUtil.toList(list.toString(), ShopType.class);
            List<ShopType> typeList = jsonArray.toList(ShopType.class);
            return Result.ok(typeList);
        }
        //redis无数据->查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //判断店铺是否存在
        if(typeList==null){
            //不存在返回404
            return Result.fail("404");
        }
        //存在则缓存数据到redis
        List<String> stringList = new ArrayList<>();
        for (ShopType shopType : typeList) {
            stringList.add(JSONUtil.toJsonStr(shopType));
        }
        redisTemplate.opsForList().rightPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY,stringList);
        //返回数据
        return Result.ok(typeList);
    }
}
