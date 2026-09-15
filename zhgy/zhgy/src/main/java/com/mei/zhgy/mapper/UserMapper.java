package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface UserMapper {

    /**
     * 根据用户名查询用户
     * @param username
     * @return
     */
    @Select("select * from user where username=#{username}")
    User getByUsername(String username);

    /**
     * 插入新用户
     * @param user
     */
    @Insert("insert into user (username, password, role) values (#{username}, #{password}, #{role})")
    void insert(User user);
    
    /**
     * 根据用户ID查询其拥有的农场
     * @param userId
     * @return
     */
    @Select("select id from farm where user_id = #{userId}")
    List<Integer> getFarmIdsByUserId(Integer userId);
    
    /**
     * 查询所有农场ID（供管理员使用）
     * @return
     */
    @Select("select id from farm")
    List<Integer> getAllFarmIds();
    
    /**
     * 根据用户ID查询用户
     * @param id
     * @return
     */
    @Select("select * from user where id = #{id}")
    User getById(Integer id);
    
    /**
     * 查询所有非管理员用户
     * @return
     */
    @Select("select * from user where role != 'admin'")
    List<User> getAllNonAdminUsers();
    
    /**
     * 更新用户密码
     * @param username 用户名
     * @param newPassword 新密码
     */
    @Update("update user set password = #{newPassword} where username = #{username}")
    void updatePasswordByUsername(@Param("username") String username, @Param("newPassword") String newPassword);
    
    /**
     * 根据用户名删除用户
     * @param username 用户名
     */
    @Delete("delete from user where username = #{username}")
    void deleteByUsername(@Param("username") String username);
}