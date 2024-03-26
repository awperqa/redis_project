package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Autowired
    private IFollowService iFollowService;

    @PutMapping("{id}/{isFollow}")
    public Result follow(@PathVariable Long id,@PathVariable Boolean isFollow){
        return iFollowService.follow(id,isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result follow(@PathVariable Long id){
        return iFollowService.follow(id);
    }

    @GetMapping("/common/{id}")
    public Result common(@PathVariable Long id){
        return iFollowService.common(id);
    }



}
