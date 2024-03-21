package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private IUserService iUserService;

    private static final String FOLLOW_KEY = "follow:";

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //关注 取关
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW_KEY+userId;
        //判断是否关注
        //未关注 进行关注
        if(isFollow){
           Follow follow = new Follow();
           follow.setFollowUserId(followUserId);
           follow.setUserId(userId);
           boolean save = save(follow);
           //保存数据到redis
           if(!save){
               return Result.fail("保存到数据库失败");
           }
           redisTemplate.opsForSet().add(key,followUserId.toString());
        }else {
            //已关注 取消
            boolean remove = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            if(!remove){
                return Result.fail("数据库操作失败");
            }
            redisTemplate.opsForSet().remove(key,followUserId.toString());
        }
        return Result.ok();
    }

    //判断是否关注
    @Override
    public Result follow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_Id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count>0);
    }

    @Override
    public Result common(Long id) {
        //获取共同关注
        Long userId = UserHolder.getUser().getId();
        String key1 =FOLLOW_KEY+userId;
        String key2 =FOLLOW_KEY+id;
        //redis中获取相同id共同关注
        Set<String> followIds = redisTemplate.opsForSet().intersect(key1, key2);
        if(followIds==null||followIds.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> list = followIds.stream().map(Long::valueOf).collect(Collectors.toList());
        //返回用户信息
        List<User> users = iUserService.listByIds(list);
        List<UserDTO> userDTOS = BeanUtil.copyToList(users, UserDTO.class);
        return Result.ok(userDTOS);
    }
}
