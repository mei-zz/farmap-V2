package com.mei.zhgy.service.impl;

import com.mei.zhgy.entity.FarmRecord;
import com.mei.zhgy.mapper.FarmRecordMapper;
import com.mei.zhgy.result.NoPageResult;
import com.mei.zhgy.result.PageResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FarmRecordService implements com.mei.zhgy.service.FarmRecordService {

    @Autowired
    private FarmRecordMapper farmRecordMapper;
    /**
     * 通过前端 “园区”，“树体”，“水肥”，“病虫”选择来查询农事记录
     * 目前是分页查询，每一页显示全部内容
     * @param recordid
     * @return
     */
    @Override
    public NoPageResult queryRecordByid(Integer recordid) {
        List<FarmRecord> farmRecord = farmRecordMapper.selectRecord(recordid);
        NoPageResult result = new NoPageResult();
        result.setRecords(farmRecord);
        return result;
    }
}
