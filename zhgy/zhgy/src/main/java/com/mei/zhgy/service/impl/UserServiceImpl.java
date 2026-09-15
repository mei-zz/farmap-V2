package com.mei.zhgy.service.impl;

import com.mei.zhgy.constant.MessageConstant;
import com.mei.zhgy.dto.UserLoginDTO;
import com.mei.zhgy.dto.UserRegisterDTO;
import com.mei.zhgy.entity.User;
import com.mei.zhgy.exception.AccountNotFoundException;
import com.mei.zhgy.mapper.FarmMapper;
import com.mei.zhgy.mapper.ModelMapper;
import com.mei.zhgy.mapper.UserMapper;
import com.mei.zhgy.service.UserService;
import com.mei.zhgy.vo.CropInfoVO;
import com.mei.zhgy.vo.FarmCropVO;
import com.mei.zhgy.vo.SimplifiedFarmVO;
import com.mei.zhgy.vo.UserLoginResponseVO;
import com.mei.zhgy.vo.UserLoginSimpleResponseVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class UserServiceImpl implements UserService {
    @Autowired
    private UserMapper userMapper;
    
    @Autowired
    private FarmMapper farmMapper;
    
    @Autowired
    private ModelMapper modelMapper;

    /**
     * 用户登录
     * @param userLoginDTO
     * @return
     */
    @Override
    public User login(UserLoginDTO userLoginDTO) {
        String username = userLoginDTO.getUsername();
        String password = userLoginDTO.getPassword();

        User user = userMapper.getByUsername(username);
        //用户不存在
        if (user==null)
        {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        //密码错误情况
        if (!password.equals(user.getPassword())) {
            //密码错误
            throw new AccountNotFoundException(MessageConstant.PASSWORD_ERROR);
        }
        //返回user实体
        return user;
    }

    /**
     * 用户注册
     * @param userRegisterDTO
     * @return
     */
    @Override
    public User register(UserRegisterDTO userRegisterDTO) {
        String username = userRegisterDTO.getUsername();
        String password = userRegisterDTO.getPassword();
        String role = userRegisterDTO.getRole();

        // 检查用户名是否已存在
        User existingUser = userMapper.getByUsername(username);
        if (existingUser != null) {
            throw new RuntimeException("用户名已存在");
        }

        // 创建新用户
        User newUser = User.builder()
                .username(username)
                .password(password) // 实际项目中应该加密存储
                .role(role != null ? role : "guest") // 默认为guest角色，可以是user, expert或admin
                .build();

        userMapper.insert(newUser);
        return newUser;
    }
    
    /**
     * 根据用户ID获取用户信息
     * @param userId
     * @return
     */
    @Override
    public User getUserById(Integer userId) {
        return userMapper.getById(userId);
    }
    
    /**
     * 根据用户名获取用户信息
     * @param username
     * @return
     */
    @Override
    public User getByUserName(String username) {
        return userMapper.getByUsername(username);
    }
    
    /**
     * 获取所有非管理员用户
     * @return
     */
    @Override
    public List<User> getAllNonAdminUsers() {
        return userMapper.getAllNonAdminUsers();
    }
    
    /**
     * 获取guest用户信息
     * @return
     */
    @Override
    public User getGuestUser() {
        return userMapper.getByUsername("guest");
    }
    
    /**
     * 修改用户密码
     * @param targetUser 目标用户
     * @param newPassword 新密码
     * @param currentUser 当前操作用户
     * @return 是否修改成功
     */
    @Override
    public boolean changePassword(String targetUser, String newPassword, User currentUser) {
        // 检查目标用户是否存在
        User user = userMapper.getByUsername(targetUser);
        if (user == null) {
            throw new AccountNotFoundException("目标用户不存在");
        }
        
        // 权限检查：只有管理员或者用户自己可以修改密码
        if (!"admin".equals(currentUser.getRole()) && !currentUser.getUsername().equals(targetUser)) {
            throw new RuntimeException("没有权限修改该用户密码");
        }
        
        // 更新密码
        userMapper.updatePasswordByUsername(targetUser, newPassword);
        return true;
    }
    
    /**
     * 删除用户
     * @param targetUser 目标用户
     * @param currentUser 当前操作用户
     * @return 是否删除成功
     */
    @Override
    public boolean deleteUser(String targetUser, User currentUser) {
        // 检查目标用户是否存在
        User user = userMapper.getByUsername(targetUser);
        if (user == null) {
            throw new AccountNotFoundException("目标用户不存在");
        }
        
        // 权限检查：只有管理员可以删除用户
        if (!"admin".equals(currentUser.getRole())) {
            throw new RuntimeException("没有权限删除用户");
        }
        
        // 不能删除自己
        if (currentUser.getUsername().equals(targetUser)) {
            throw new RuntimeException("不能删除自己");
        }
        
        // 不能删除admin用户
        if ("admin".equals(user.getRole())) {
            throw new RuntimeException("不能删除管理员用户");
        }
        
        // 删除用户
        userMapper.deleteByUsername(targetUser);
        return true;
    }
    
    /**
     * 构建用户登录响应VO
     * @param user
     * @param token
     * @return
     */
    @Override
    public UserLoginResponseVO buildUserLoginResponse(User user, String token) {
        // 构造用户信息
        UserLoginResponseVO.UserVO userVO = UserLoginResponseVO.UserVO.builder()
                .name(user.getUsername())
                .role(user.getRole())
                .build();
        
        // 根据用户角色获取农场信息
        List<UserLoginResponseVO.FarmVO> farms = getUserFarms(user);
        
        // 构造最终响应VO
        return UserLoginResponseVO.builder()
                .user(userVO)
                .farms(farms)
                .token(token)
                .status(0) // 0表示正常情况
                .build();
    }
    
    /**
     * 构建简化版用户登录响应VO
     * @param user
     * @param token
     * @return
     */
    @Override
    public UserLoginSimpleResponseVO buildUserLoginSimpleResponse(User user, String token) {
        // 构造用户信息
        UserLoginSimpleResponseVO.UserVO userVO = UserLoginSimpleResponseVO.UserVO.builder()
                .name(user.getUsername())
                .role(user.getRole())
                .build();
        
        // 根据用户角色获取简化版农场信息
        List<SimplifiedFarmVO> farms = getUserSimpleFarms(user);
        
        // 构造最终响应VO
        return UserLoginSimpleResponseVO.builder()
                .user(userVO)
                .farms(farms)
                .token(token)
                .status(0) // 0表示正常情况
                .build();
    }
    
    /**
     * 根据用户角色获取农场信息
     * @param user
     * @return
     */
    private List<UserLoginResponseVO.FarmVO> getUserFarms(User user) {
        List<Integer> farmIds;
        
        // 管理员可以看到所有农场，普通用户只能看到自己的农场
        if ("admin".equals(user.getRole())) {
            // 管理员获取所有农场
            farmIds = userMapper.getAllFarmIds();
        } else {
            // 普通用户（包括guest）只获取自己的农场
            farmIds = userMapper.getFarmIdsByUserId(user.getId());
        }
        
        // 根据农场ID获取农场详细信息
        List<UserLoginResponseVO.FarmVO> farms = new ArrayList<>();
        for (Integer farmId : farmIds) {
            UserLoginResponseVO.FarmVO farm = farmMapper.getFarmById(farmId);
            if (farm != null) {
                // 获取农场组件
                List<String> components = farmMapper.getComponentsByFarmId(farmId);
                farm.setComponents(components);
                
                // 获取农场位置信息
                List<UserLoginResponseVO.FarmVO.LocationVO> locations = farmMapper.getLocationsByFarmId(farmId);
                farm.setLocations(locations);
                
                // 获取农场作物信息
                List<FarmCropVO> crops = farmMapper.getCropsByFarmId(farmId);
                farm.setCrops(crops);
                
                
                farms.add(farm);
            }
        }
        
        return farms;
    }
    
    /**
     * 根据用户角色获取简化版农场信息
     * @param user
     * @return
     */
    private List<SimplifiedFarmVO> getUserSimpleFarms(User user) {
        List<Integer> farmIds;
        
        // 管理员可以看到所有农场，普通用户只能看到自己的农场
        if ("admin".equals(user.getRole())) {
            // 管理员获取所有农场
            farmIds = userMapper.getAllFarmIds();
        } else {
            // 普通用户（包括guest）只获取自己的农场
            farmIds = userMapper.getFarmIdsByUserId(user.getId());
        }
        
        // 根据农场ID获取简化的农场信息（只包含id和name）
        List<SimplifiedFarmVO> farms = new ArrayList<>();
        for (Integer farmId : farmIds) {
            SimplifiedFarmVO simplifiedFarm = farmMapper.getSimpleFarmById(farmId);
            if (simplifiedFarm != null) {
                farms.add(simplifiedFarm);
            }
        }
        
        return farms;
    }
}