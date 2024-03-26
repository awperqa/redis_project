package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Autowired
    private UserServiceImpl userService;
    @Autowired
    private RedisTemplate redisTemplate;
    @Autowired
    private IFollowService iFollowService;

    @Override
    public Result queryById(Long id) {
        Blog blog = getById(id);
        if(blog==null){
            return Result.fail("无用户信息");
        }
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    public void isBlogLiked(Blog blog){
        UserDTO userDTO = UserHolder.getUser();
        if(userDTO == null){
            return;
        }
        Long userId= userDTO.getId();
        String key = RedisConstants.BLOG_LIKED_KEY+userId;
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }


    @Override
    public Result likeBlog(Long id) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key =RedisConstants.BLOG_LIKED_KEY + id;
        //判断当前用户是否点过赞
        Double score = redisTemplate.opsForZSet().score(key, userId.toString());
        //未点赞，可以点赞
        if(score==null){
            //数据库点赞数加1
            boolean update = update().setSql("liked = liked + 1").eq("id", id).update();
            //保存用户到redis中
            if(update){
                redisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
            return Result.ok();
        }
        //已点赞
        //数据库减一
        boolean update = update().setSql("liked = liked - 1").eq("id", id).update();
        //redis中去除用户
        if(update){
            redisTemplate.opsForZSet().remove(key,userId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY+id;
        //查询top5的点赞用户
        Set<String> set = redisTemplate.opsForZSet().range(key, 0, 4);
        if(set==null||set.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //得到用户id;
        List<Long> ids = set.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询用户
        List<UserDTO> collect = userService.listByIds(ids).stream().map(s -> BeanUtil.toBean(s, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(collect);
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        boolean save = save(blog);
        if(!save){
            //blog保存失败
            return Result.fail("保存失败");
        }
        //获取粉丝
        List<Follow> followList = iFollowService.query().eq("follow_user_id", user.getId()).list();
        //推送到所有粉丝
        for (Follow follow : followList) {
            Long userId = follow.getUserId();
            String key = RedisConstants.FEED_KEY + userId;
            redisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {
        //查询用户id
        Long userId = UserHolder.getUser().getId();
        //获取收件箱
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> blogIds = redisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        if(blogIds==null||blogIds.isEmpty()){
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>();
        //解析所有日志的blogID  score  offset(相同score数)
        Long minTime = 0L;
        Integer os = 1;
        for (ZSetOperations.TypedTuple<String> blog : blogIds) {
            String id = blog.getValue();
            ids.add(Long.valueOf(id));
            long score = blog.getScore().longValue();
            if(score==minTime){
                os++;
            }else{
                minTime = score;
                os=1;
            }
        }
        //查询数据
        String idStr = StrUtil.join(",", ids);
        List<Blog>blogs = query().in("id",ids).last("order by field(id,"+idStr+")").list();

        for (Blog blog : blogs) {
            Long blogUserId = blog.getUserId();
            User user = userService.getById(blogUserId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        }

        //封装返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        scrollResult.setList(blogs);
        return  Result.ok(scrollResult);
    }


}
