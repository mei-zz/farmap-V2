package com.mei.zhgy.service;

import com.mei.zhgy.dto.UserLoginDTO;
import com.mei.zhgy.dto.UserRegisterDTO;
import com.mei.zhgy.entity.User;
import com.mei.zhgy.vo.UserLoginResponseVO;
import com.mei.zhgy.vo.UserLoginSimpleResponseVO;

import java.util.List;

public interface UserService {

    /**
     * 用户登录
     * @param userLoginDTO
     * @return
     */
    User login(UserLoginDTO userLoginDTO);

    /**
     * 用户注册
     * @param userRegisterDTO
     * @return
     */
    User register(UserRegisterDTO userRegisterDTO);
    
    /**
     * 根据用户ID获取用户信息
     * @param userId
     * @return
     */
    User getUserById(Integer userId);
    
    /**
     * 根据用户名获取用户信息
     * @param username
     * @return
     */
    User getByUserName(String username);
    
    /**
     * 获取guest用户信息
     * @return
     */
    User getGuestUser();
    
    /**
     * 获取所有非管理员用户
     * @return
     */
    List<User> getAllNonAdminUsers();
    
    /**
     * 构建用户登录响应VO
     * @param user
     * @param token
     * @return
     */
    UserLoginResponseVO buildUserLoginResponse(User user, String token);
    
    /**
     * 构建简化版用户登录响应VO
     * @param user
     * @param token
     * @return
     */
    UserLoginSimpleResponseVO buildUserLoginSimpleResponse(User user, String token);
    
    /**
     * 修改用户密码
     * @param targetUser 目标用户
     * @param newPassword 新密码
     * @param currentUser 当前操作用户
     * @return 是否修改成功
     */
    boolean changePassword(String targetUser, String newPassword, User currentUser);
    
    /**
     * 删除用户
     * @param targetUser 目标用户
     * @param currentUser 当前操作用户
     * @return 是否删除成功
     */
    boolean deleteUser(String targetUser, User currentUser);
}