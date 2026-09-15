package com.mei.zhgy.controller;

import com.mei.zhgy.result.NoPageResult;
import com.mei.zhgy.result.PageResult;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.FarmRecordService;
import io.swagger.annotations.Api;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/farmrecord")
@Api(tags = "农事记录相关接口")
public class FarmRecordController {
    @Autowired
    private FarmRecordService farmRecordService;

    @GetMapping("/{recordid}")
    public Result getFarmRecord(@PathVariable("recordid") Integer recordid)
    {
        NoPageResult result = farmRecordService.queryRecordByid(recordid);
        return Result.success(result);
    }
}
