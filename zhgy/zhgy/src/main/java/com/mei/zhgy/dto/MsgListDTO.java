package com.mei.zhgy.dto;

import lombok.Data;

@Data
public class MsgListDTO {
    //页码
    private int page;

    //每页记录数
    private int pageSize;
}
