package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.BloomFilter;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private ShopServiceImpl shopService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private IUserService iUserService;
    @Autowired
    private RedisTemplate redisTemplate;

//    @Test
    void queryTest(){
        //select * from user
        List<User> userList = iUserService.query().list();
        for (User user : userList) {
            String phone = user.getPhone();

            //存在保存用户信息到redis Hash
            //生成token
            String token = UUID.randomUUID().toString()+phone.substring(phone.length()-4);
            //user转成HashMap
            UserDTO userDTO = new UserDTO();
            BeanUtils.copyProperties(user,userDTO);
            Map<String, Object> map = BeanUtil.beanToMap(userDTO);
            //储存到redis
            String key = RedisConstants.LOGIN_USER_KEY+token;
            redisTemplate.opsForHash().putAll(key,map);
            //设置有效期
            //redisTemplate.expire(key,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
            System.out.println(token);
        }
    }
    //@Test
    public void shopTest(){
        List<Shop> list = shopService.list();
        Map<Long, List<Shop>> listMap = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : listMap.entrySet()) {
            Long typeId = entry.getKey();
            String key = "shop:geo:"+typeId;
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }
            redisTemplate.opsForGeo().add(key,locations);
        }
    }
    @Test
    public void  loadShop(){
        RBloomFilter<Long> bloomFilter = BloomFilter.getBloomFilter();
        List<Shop> shops = shopService.list();
        for (Shop shop : shops) {
            log.info("加载入布隆过滤器:{}",shop.getId());
            bloomFilter.add(shop.getId());
        }
    }

}
