package com.mei.zhgy.mapper;

import com.github.pagehelper.Page;
import com.mei.zhgy.dto.MsgListDTO;
import com.mei.zhgy.entity.MsgList;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MsgListMapper {
    @Select("select * from msglist")
    Page<MsgList> pageQuery(MsgListDTO msgListDTO);
}
