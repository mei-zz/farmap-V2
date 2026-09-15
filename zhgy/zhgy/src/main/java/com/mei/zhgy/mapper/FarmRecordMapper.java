package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.FarmRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FarmRecordMapper {
    @Select("select a.date,a.farm_addr,b.username,a.record_img from agrirecord a left join user b on a.userid=b.id where a.type=#{recordid}")
    List<FarmRecord> selectRecord(Integer recordid);

}
