package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.UserRequest;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface UserRequestMapper {
    
    @Insert("INSERT INTO user_requests (request_id, user_id, image_urls, image_count, status) " +
            "VALUES (#{requestId}, #{userId}, #{imageUrls}, #{imageCount}, #{status})")
    void insert(UserRequest userRequest);
    
    @Select("SELECT * FROM user_requests WHERE request_id = #{requestId}")
    UserRequest getByRequestId(String requestId);
    
    @Update("UPDATE user_requests SET status = #{status} WHERE request_id = #{requestId}")
    void updateStatus(UserRequest userRequest);
    
    /**
     * 查询所有状态为3（已达成共识）的案例
     * @return 案例列表
     */
    @Select("SELECT * FROM user_requests WHERE status = 3")
    List<UserRequest> getConsensusCases();
    
    /**
     * 更新案例状态为4（已加入外部数据库）
     * @param requestId 案例ID
     */
    @Update("UPDATE user_requests SET status = 4 WHERE request_id = #{requestId}")
    void updateStatusToStored(@Param("requestId") String requestId);
    
    /**
     * 根据状态查询用户请求
     * @param status 状态
     * @return 用户请求列表
     */
    @Select("SELECT * FROM user_requests WHERE status = #{status}")
    List<UserRequest> selectByStatus(Integer status);
}