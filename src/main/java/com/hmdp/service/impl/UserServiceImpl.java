package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private RedisTemplate redisTemplate;

    //发送短信验证码
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号格式
        if(RegexUtils.isPhoneInvalid(phone)){
            Result.fail("手机号格式错误");
        }

        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到redis
        redisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY +phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //session.setAttribute("code",code);
        //发送验证码
        log.debug("发送成功，验证码:{}",code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            Result.fail("手机号格式错误");
        }
        //校验验证码
        String code = (String) redisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if(code == null || !code.equals(loginForm.getCode())){
            Result.fail("验证码错误");
        }
        //校验验证码成功->根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //不存在，完成注册
        if(user==null){
            user = createUserWithPhone(phone);
        }

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
        redisTemplate.expire(key,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +RandomUtil.randomNumbers(10)+phone.substring(phone.length()-4));
        save(user);
        log.info("添加成功:{}",user);
        return user;
    }
}
