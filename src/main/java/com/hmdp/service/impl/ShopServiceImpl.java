package com.hmdp.service.impl;

import ch.qos.logback.core.util.COWArrayList;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.*;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.domain.geo.BoundingBox;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
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
    @Autowired
    private CacheRedis cacheRedis;
    @Autowired
    private static RedissonClient redissonClient;






    @Override
    public Result queryById(Long id) {
        RBloomFilter<Long> bloomFilter = BloomFilter.getBloomFilter();
        if(!bloomFilter.contains(id)){
            //不存在 一定不存在
            return Result.fail("店铺不存在");
        }
        //存在 (可能不存在)
        //查询redis是否有缓存
        String keyPreFix = RedisConstants.CACHE_SHOP_KEY;
        String json = (String) redisTemplate.opsForValue().get(keyPreFix + id);
        //有，返回
        if(json!=null){
            Shop shop = JSONUtil.toBean(json, Shop.class);
            return Result.ok(shop);
        }
        //无，查询数据库
        Shop shop = getById(id);
        if(shop==null){
            return Result.fail("无商铺信息");
        }
        bloomFilter.add(shop.getId());
        cacheRedis.set(keyPreFix+id,shop,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //Shop shop = cacheRedis.cacheBlank(keyPreFix, id, Shop.class, this::getById, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //Shop shop = cacheRedis.CacheExpire(keyPreFix, id, Shop.class, this::getById, LocalDateTime.now().plusSeconds(20l));
        if (shop == null) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);



//        //查询redis
//        String shopJson = (String) redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //redis有数据直接返回
//        if (StrUtil.isNotBlank(shopJson)) {
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
//        }
//        if (shopJson != null) {
//            return Result.fail("店铺不存在!");
//        }
//        //通过互斥锁避免缓存击穿
//        //获取互斥锁
//        String key = RedisConstants.LOCK_SHOP_KEY + id;
//        Shop shop = null;
//        try {
//            Boolean lock = tryLock(key);
//            //判断锁是否创建成功
//            if (!lock) {
//                //有锁休眠重试
//                Thread.sleep(50);
//                return queryById(id);
//            }
//            //成功
//            //进行二次检查 redis缓存
//            String check = (String) redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//            //redis有数据直接返回
//            if (StrUtil.isNotBlank(check)) {
//                Shop shopBean = JSONUtil.toBean(check, Shop.class);
//                return Result.ok(shopBean);
//            }
//            if (check != null) {
//                return Result.fail("店铺不存在!");
//            }
//            //查询数据库
//            shop = getById(id);
//            //判断店铺是否存在
//            if (shop == null) {
//                //不存在返回404
//                redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
//            }
//            //存在则缓存数据到redis
//            String toJsonStr = JSONUtil.toJsonStr(shop);
//            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, toJsonStr, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        } catch (InterruptedException e) {
//            throw new RuntimeException();
//        } finally {
//            //释放互斥锁
//            unLock(key);
//        }
//        //返回数据
//        return Result.ok(shop);
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
            } catch (InterruptedException e) {
                e.printStackTrace();
            }finally {
                unLock(lockKey);
            }
        });
        return shop;
}

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否需要坐标查询
        if(x == null || y == null){
            Page<Shop> shopPage = query().eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(shopPage.getRecords());
        }
        //计算分页参数
        int from = (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = SystemConstants.DEFAULT_PAGE_SIZE*current;
        //查询redis 按照距离排序 分页
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = redisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y), new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        //返回排序后的shopList
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        //解析id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if(content.size() <= from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(content.size());
        Map<String,Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(result->{
            String shopId = result.getContent().getName();
            Distance distance = result.getDistance();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId,distance);
        });
        //根据id查询店铺 返回
        String idStr = StrUtil.join(",",ids);
        List<Shop> shopList = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shopList);
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
        Thread.sleep(100);
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
