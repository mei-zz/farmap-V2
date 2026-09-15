package com.mei.zhgy.controller;

import com.mei.zhgy.dto.MsgListDTO;
import com.mei.zhgy.result.PageResult;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.MsgListService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/messages")
public class MsgListController {
    @Autowired
    private MsgListService msgListService;

    @GetMapping("/page")
    public Result getMsgList(@RequestBody MsgListDTO msgListDTO)
    {
        log.info("消息列表功能的分页查询");
        PageResult pageResult = msgListService.pageQuery(msgListDTO);
        return Result.success(pageResult);
    }
}
