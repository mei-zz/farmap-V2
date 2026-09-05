package com.mei.zhgy.service;

import com.mei.zhgy.dto.MsgListDTO;
import com.mei.zhgy.result.PageResult;
import org.springframework.stereotype.Service;

@Service
public interface MsgListService {
    /**
     * 分页查询消息列表
     * @return
     */
    PageResult pageQuery(MsgListDTO msgListDTO);
}
