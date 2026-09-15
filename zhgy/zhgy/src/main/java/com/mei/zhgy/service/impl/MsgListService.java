package com.mei.zhgy.service.impl;

import com.mei.zhgy.dto.MsgListDTO;
import com.mei.zhgy.entity.MsgList;
import com.mei.zhgy.mapper.MsgListMapper;
import com.mei.zhgy.result.PageResult;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MsgListService implements com.mei.zhgy.service.MsgListService {
    @Autowired
    private MsgListMapper msgListMapper;
    /**
     * 分页查询消息列表
     * @return
     */
    @Override
    public PageResult pageQuery(MsgListDTO msgListDTO) {
        PageHelper.startPage(msgListDTO.getPage(), msgListDTO.getPageSize());
        //sql语句会自动进行分页，limit
        Page<MsgList> page = msgListMapper.pageQuery(msgListDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }
}
