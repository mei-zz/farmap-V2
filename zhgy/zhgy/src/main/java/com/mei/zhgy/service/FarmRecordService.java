package com.mei.zhgy.service;

import com.mei.zhgy.result.NoPageResult;
import com.mei.zhgy.result.PageResult;

public interface FarmRecordService {
    /**
     * 通过前端 “园区”，“树体”，“水肥”，“病虫”选择来查询农事记录
     * @param recordid
     * @return
     */
    NoPageResult queryRecordByid(Integer recordid);
}
