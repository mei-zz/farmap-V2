package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.InitialResult;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface InitialResultMapper {
    
    @Insert("INSERT INTO initial_results (result_id, request_id, json_data, generate_time, model_version) " +
            "VALUES (#{resultId}, #{requestId}, #{jsonData}, #{generateTime}, #{modelVersion})")
    void insert(InitialResult initialResult);
    
    @Select("SELECT * FROM initial_results WHERE request_id = #{requestId}")
    InitialResult getByRequestId(String requestId);
}