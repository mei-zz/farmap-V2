package com.mei.zhgy.controller;

import com.mei.zhgy.constant.JwtClaimsConstant;
import com.mei.zhgy.constant.MessageConstant;
import com.mei.zhgy.dto.ChangePasswordDTO;
import com.mei.zhgy.dto.CreateModelListRequest;
import com.mei.zhgy.dto.DeleteModelRequest;
import com.mei.zhgy.dto.DeleteUserDTO;
import com.mei.zhgy.dto.ExpertRevisionDTO;
import com.mei.zhgy.dto.ImageAnalyzeDTO;
import com.mei.zhgy.dto.MapTreeDTO;
import com.mei.zhgy.dto.MsgListDTO;
import com.mei.zhgy.dto.SignupRequest;
import com.mei.zhgy.dto.TokenValidationDTO;
import com.mei.zhgy.dto.UpdateGuidanceDTO;
import com.mei.zhgy.dto.UpdateModelListRequest;
import com.mei.zhgy.dto.UserLoginDTO;
import com.mei.zhgy.dto.UserRegisterDTO;
import com.mei.zhgy.entity.User;
import com.mei.zhgy.mapper.FarmMapper;
import com.mei.zhgy.properties.JwtProperties;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.UserService;
import com.mei.zhgy.utils.JwtUtil;
import com.mei.zhgy.vo.CropInfoVO;
import com.mei.zhgy.vo.FarmCropVO;
import com.mei.zhgy.vo.UserLoginResponseVO;
import com.mei.zhgy.vo.UserLoginSimpleResponseVO;
import com.mei.zhgy.vo.UserLoginVO;
import com.mei.zhgy.vo.SignupResponse;
import com.mei.zhgy.vo.AdminCheckResponse;
import com.mei.zhgy.vo.GetAllUsersResponse;
import com.mei.zhgy.vo.ChangePasswordResponse;
import com.mei.zhgy.vo.DeleteUserResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@Slf4j
@RequestMapping("/user")
@Api(tags = "用户相关接口")
public class UserController {
    @Autowired
    private UserService userService;
    @Autowired
    private JwtProperties jwtProperties;
    @Autowired
    private FarmMapper farmMapper;

    /**
     *用户登录
     * @return
     */
    @PostMapping("/login")
    @ApiOperation(value = "用户登录")
    public Result<UserLoginSimpleResponseVO> userLogin(@RequestBody UserLoginDTO userLoginDTO)
    {
        log.info("员工登录：{}", userLoginDTO);
        User user =  userService.login(userLoginDTO);

        Map<String, Object> claims = new HashMap<>();

        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        claims.put(JwtClaimsConstant.ROLE,user.getRole());

        String token = JwtUtil.createJWT(
                jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims
        );
        
        // 构造新的返回格式
        UserLoginSimpleResponseVO responseVO = userService.buildUserLoginSimpleResponse(user, token);
        
        return Result.success(responseVO);
    }

    /**
     * 用户注册
     * @param userRegisterDTO
     * @return
     */
    @PostMapping("/register")
    @ApiOperation(value = "用户注册")
    public Result<UserLoginSimpleResponseVO> register(@RequestBody UserRegisterDTO userRegisterDTO) {
        log.info("用户注册：{}", userRegisterDTO);
        
        try {
            User user = userService.register(userRegisterDTO);
            
            Map<String, Object> claims = new HashMap<>();
            claims.put(JwtClaimsConstant.USER_ID, user.getId());
            claims.put(JwtClaimsConstant.ROLE, user.getRole());
            
            String token = JwtUtil.createJWT(
                    jwtProperties.getUserSecretKey(),
                    jwtProperties.getUserTtl(),
                    claims
            );
            
            // 构造新的返回格式
            UserLoginSimpleResponseVO responseVO = userService.buildUserLoginSimpleResponse(user, token);
            
            return Result.success(responseVO);
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
    
    /**
     * 用户注册(signup) - 符合新要求的接口
     * @param signupRequest
     * @return
     */
    @PostMapping("/signup")
    @ApiOperation(value = "用户注册(signup)")
    public ResponseEntity<SignupResponse> signup(@RequestBody SignupRequest signupRequest) {
        log.info("用户注册(signup)：{}", signupRequest);
        
        try {
            // 检查用户名是否已存在
            User existingUser = userService.getByUserName(signupRequest.getName());
            if (existingUser != null) {
                SignupResponse response = SignupResponse.builder()
                        .message("Username already exists")
                        .build();
                return ResponseEntity.badRequest().body(response);
            }
            
            // 创建新用户
            UserRegisterDTO userRegisterDTO = new UserRegisterDTO();
            userRegisterDTO.setUsername(signupRequest.getName());
            userRegisterDTO.setPassword(signupRequest.getPassword());
            userRegisterDTO.setRole("guest"); // 默认角色为guest
            
            User user = userService.register(userRegisterDTO);
            
            // 生成JWT token
            Map<String, Object> claims = new HashMap<>();
            claims.put(JwtClaimsConstant.USER_ID, user.getId());
            claims.put(JwtClaimsConstant.ROLE, user.getRole());
            
            String token = JwtUtil.createJWT(
                    jwtProperties.getUserSecretKey(),
                    jwtProperties.getUserTtl(),
                    claims
            );
            
            // 构造响应
            SignupResponse.User responseUser = SignupResponse.User.builder()
                    .name(user.getUsername())
                    .role(user.getRole())
                    .token(token)
                    .build();
            
            SignupResponse response = SignupResponse.builder()
                    .message("User signed up successfully")
                    .user(responseUser)
                    .build();
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("注册失败", e);
            SignupResponse response = SignupResponse.builder()
                    .message("Registration failed: " + e.getMessage())
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * 获取所有非管理员用户
     * @param authorization
     * @return
     */
    @GetMapping("/all")
    @ApiOperation(value = "获取所有非管理员用户")
    public ResponseEntity<GetAllUsersResponse> getAllUsers(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("获取所有非管理员用户，Authorization: {}", authorization);
        
        // 从Authorization header中提取token (Bearer token格式)
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
            log.info("提取的token: {}", token);
        }
        
        // 如果没有提供token
        if (token == null || token.isEmpty()) {
            log.warn("未提供token或token为空");
            GetAllUsersResponse response = GetAllUsersResponse.builder()
                    .message("Token is invalid")
                    .users(new ArrayList<>())
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
        
        try {
            // 尝试解析token
            log.info("正在使用密钥解析token: {}", jwtProperties.getUserSecretKey());
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            log.info("解析出的claims: {}", claims);
            
            // 获取用户角色
            String role = claims.get(JwtClaimsConstant.ROLE).toString();
            log.info("用户角色: {}", role);
            
            // 检查是否为管理员
            if (!"admin".equals(role)) {
                GetAllUsersResponse response = GetAllUsersResponse.builder()
                        .message("Only administrators can access this resource")
                        .users(new ArrayList<>())
                        .build();
                return ResponseEntity.badRequest().body(response);
            }
            
            // 获取所有非管理员用户
            List<User> users = userService.getAllNonAdminUsers();
            log.info("获取到 {} 个非管理员用户", users.size());
            
            // 转换为响应格式
            List<GetAllUsersResponse.User> responseUsers = new ArrayList<>();
            for (User user : users) {
                GetAllUsersResponse.User responseUser = GetAllUsersResponse.User.builder()
                        .id(user.getId())
                        .name(user.getUsername())
                        .role(user.getRole())
                        .build();
                responseUsers.add(responseUser);
            }
            
            GetAllUsersResponse response = GetAllUsersResponse.builder()
                    .message("Success to get all users")
                    .users(responseUsers)
                    .build();
            
            return ResponseEntity.ok(response);
        } catch (ExpiredJwtException e) {
            log.warn("Token已过期: {}", e.getMessage());
            GetAllUsersResponse response = GetAllUsersResponse.builder()
                    .message("Token is invalid")
                    .users(new ArrayList<>())
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (SignatureException e) {
            log.warn("Token签名无效: {}", e.getMessage());
            GetAllUsersResponse response = GetAllUsersResponse.builder()
                    .message("Token is invalid")
                    .users(new ArrayList<>())
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (MalformedJwtException e) {
            log.warn("Token格式错误: {}", e.getMessage());
            GetAllUsersResponse response = GetAllUsersResponse.builder()
                    .message("Token is invalid")
                    .users(new ArrayList<>())
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的Token: {}", e.getMessage());
            GetAllUsersResponse response = GetAllUsersResponse.builder()
                    .message("Token is invalid")
                    .users(new ArrayList<>())
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Token为空: {}", e.getMessage());
            GetAllUsersResponse response = GetAllUsersResponse.builder()
                    .message("Token is invalid")
                    .users(new ArrayList<>())
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("解析token时发生未知错误: {}", e.getMessage(), e);
            // token无效或已过期
            GetAllUsersResponse response = GetAllUsersResponse.builder()
                    .message("Token is invalid")
                    .users(new ArrayList<>())
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * 检查管理员授权接口
     * @param authorization
     * @return
     */
    @GetMapping("/check-admin")
    @ApiOperation(value = "检查管理员授权")
    public ResponseEntity<AdminCheckResponse> checkAdminAuthorization(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("检查管理员授权：{}", authorization);
        
        // 从Authorization header中提取token (Bearer token格式)
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
            log.info("提取的token: {}", token);
        }
        
        // 如果没有提供token
        if (token == null || token.isEmpty()) {
            log.warn("未提供token或token为空");
            AdminCheckResponse response = AdminCheckResponse.builder()
                    .message("Token is invalid")
                    .status(2)
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
        
        try {
            // 尝试解析token
            log.info("正在使用密钥解析token: {}", jwtProperties.getUserSecretKey());
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            log.info("解析出的claims: {}", claims);
            
            // 获取用户角色
            String role = claims.get(JwtClaimsConstant.ROLE).toString();
            log.info("用户角色: {}", role);
            
            // 检查是否为管理员
            if ("admin".equals(role)) {
                AdminCheckResponse response = AdminCheckResponse.builder()
                        .message("Admin check correct")
                        .status(0)
                        .build();
                return ResponseEntity.ok(response);
            } else {
                AdminCheckResponse response = AdminCheckResponse.builder()
                        .message("This user is not an admin")
                        .status(1)
                        .build();
                return ResponseEntity.ok(response);
            }
        } catch (ExpiredJwtException e) {
            log.warn("Token已过期: {}", e.getMessage());
            AdminCheckResponse response = AdminCheckResponse.builder()
                    .message("Token is invalid")
                    .status(2)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (SignatureException e) {
            log.warn("Token签名无效: {}", e.getMessage());
            AdminCheckResponse response = AdminCheckResponse.builder()
                    .message("Token is invalid")
                    .status(2)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (MalformedJwtException e) {
            log.warn("Token格式错误: {}", e.getMessage());
            AdminCheckResponse response = AdminCheckResponse.builder()
                    .message("Token is invalid")
                    .status(2)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的Token: {}", e.getMessage());
            AdminCheckResponse response = AdminCheckResponse.builder()
                    .message("Token is invalid")
                    .status(2)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Token为空: {}", e.getMessage());
            AdminCheckResponse response = AdminCheckResponse.builder()
                    .message("Token is invalid")
                    .status(2)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("解析token时发生未知错误: {}", e.getMessage(), e);
            // token无效或已过期
            AdminCheckResponse response = AdminCheckResponse.builder()
                    .message("Token is invalid")
                    .status(2)
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * 验证token并返回用户信息 (POST方式)
     * @param token
     * @return
     */
    @PostMapping("/validate-token")
    @ApiOperation(value = "验证token")
    public Result<UserLoginResponseVO> validateToken(@RequestHeader(value = "authentication", required = false) String token) {
        log.info("POST方式验证token：{}", token);
        
        try {
            // 尝试解析token
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            
            // token有效，获取用户信息
            Integer userId = Integer.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());
            String role = claims.get(JwtClaimsConstant.ROLE).toString();
            
            // 从数据库获取完整的用户信息
            User user = userService.getUserById(userId);
            
            // 生成新的token
            Map<String, Object> newClaims = new HashMap<>();
            newClaims.put(JwtClaimsConstant.USER_ID, userId);
            newClaims.put(JwtClaimsConstant.ROLE, role);
            
            String newToken = JwtUtil.createJWT(
                    jwtProperties.getUserSecretKey(),
                    jwtProperties.getUserTtl(),
                    newClaims
            );
            
            // 构造新的返回格式
            UserLoginResponseVO responseVO = userService.buildUserLoginResponse(user, newToken);
            
            log.info("Token验证成功：用户ID={}, 用户名={}, 角色={}", userId, user.getUsername(), role);
            return Result.success(responseVO);
        } catch (ExpiredJwtException e) {
            log.info("Token已过期，返回guest用户信息");
            // token过期，返回guest用户信息
            User guestUser = userService.login(new UserLoginDTO("guest", "123456"));
            
            Map<String, Object> guestClaims = new HashMap<>();
            guestClaims.put(JwtClaimsConstant.USER_ID, guestUser.getId());
            guestClaims.put(JwtClaimsConstant.ROLE, guestUser.getRole());
            
            String guestToken = JwtUtil.createJWT(
                    jwtProperties.getUserSecretKey(),
                    jwtProperties.getUserTtl(),
                    guestClaims
            );
            
            // 构造新的返回格式
            UserLoginResponseVO responseVO = userService.buildUserLoginResponse(guestUser, guestToken);
            
            return Result.success(responseVO);
        } catch (Exception e) {
            log.info("Token无效，返回guest用户信息");
            // token无效，返回guest用户信息
            User guestUser = userService.getGuestUser();
            
            Map<String, Object> guestClaims = new HashMap<>();
            guestClaims.put(JwtClaimsConstant.USER_ID, guestUser.getId());
            guestClaims.put(JwtClaimsConstant.ROLE, guestUser.getRole());
            
            String guestToken = JwtUtil.createJWT(
                    jwtProperties.getUserSecretKey(),
                    jwtProperties.getUserTtl(),
                    guestClaims
            );
            
            // 构造新的返回格式
            UserLoginResponseVO responseVO = userService.buildUserLoginResponse(guestUser, guestToken);
            
            return Result.success(responseVO);
        }
    }
    
    /**
     * 验证token是否有效和过期 (GET方式)
     * 网页打开时调用，通过Authorization header传递token
     * @param authorization
     * @return
     */
    @GetMapping("/validate-token")
    @ApiOperation(value = "验证token是否有效和过期(GET方式)")
    public Result<TokenValidationDTO> validateTokenGet(@RequestHeader(value = "Authorization", required = false) String authorization) {
        log.info("GET方式验证token：{}", authorization);

        // 从Authorization header中提取token (Bearer token格式)
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
        }

        // 如果没有提供token
        if (token == null || token.isEmpty()) {
            log.info("未提供token或token为空");
            return Result.success(TokenValidationDTO.builder().isValid(false).isExpired(true).build());
        }

        try {
            // 尝试解析token
            JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);

            // token有效且未过期
            log.info("Token验证成功");
            return Result.success(TokenValidationDTO.builder().isValid(true).isExpired(false).build());
        } catch (ExpiredJwtException e) {
            log.info("Token已过期");
            return Result.success(TokenValidationDTO.builder().isValid(false).isExpired(true).build());
        } catch (Exception e) {
            log.info("Token无效");
            return Result.success(TokenValidationDTO.builder().isValid(false).isExpired(true).build());
        }
    }

    /**
     * 修改用户密码接口
     * @param authorization
     * @param changePasswordDTO
     * @return
     */
    @PostMapping("/change-password")
    @ApiOperation(value = "修改用户密码")
    public ResponseEntity<ChangePasswordResponse> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ChangePasswordDTO changePasswordDTO) {
        log.info("修改用户密码：targetUser={}, Authorization={}", changePasswordDTO.getTargetUser(), authorization);
        
        // 从Authorization header中提取token (Bearer token格式)
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
            log.info("提取的token: {}", token);
        }
        
        // 如果没有提供token
        if (token == null || token.isEmpty()) {
            log.warn("未提供token或token为空");
            ChangePasswordResponse response = ChangePasswordResponse.builder()
                    .message("Token is invalid")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
        
        try {
            // 尝试解析token
            log.info("正在使用密钥解析token: {}", jwtProperties.getUserSecretKey());
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            log.info("解析出的claims: {}", claims);
            
            // 获取当前用户信息
            Integer currentUserId = Integer.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());
            String currentUserRole = claims.get(JwtClaimsConstant.ROLE).toString();
            
            // 从数据库获取当前用户完整信息
            User currentUser = userService.getUserById(currentUserId);
            if (currentUser == null) {
                ChangePasswordResponse response = ChangePasswordResponse.builder()
                        .message("当前用户不存在")
                        .status(1)
                        .build();
                return ResponseEntity.badRequest().body(response);
            }
            
            // 设置当前用户角色
            currentUser.setRole(currentUserRole);
            
            // 调用服务修改密码
            boolean success = userService.changePassword(
                    changePasswordDTO.getTargetUser(), 
                    changePasswordDTO.getNewPwd(), 
                    currentUser);
            
            if (success) {
                ChangePasswordResponse response = ChangePasswordResponse.builder()
                        .message("密码修改成功")
                        .status(0)
                        .build();
                return ResponseEntity.ok(response);
            } else {
                ChangePasswordResponse response = ChangePasswordResponse.builder()
                        .message("密码修改失败")
                        .status(1)
                        .build();
                return ResponseEntity.badRequest().body(response);
            }
        } catch (ExpiredJwtException e) {
            log.warn("Token已过期: {}", e.getMessage());
            ChangePasswordResponse response = ChangePasswordResponse.builder()
                    .message("Token已过期")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (SignatureException e) {
            log.warn("Token签名无效: {}", e.getMessage());
            ChangePasswordResponse response = ChangePasswordResponse.builder()
                    .message("Token无效")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (MalformedJwtException e) {
            log.warn("Token格式错误: {}", e.getMessage());
            ChangePasswordResponse response = ChangePasswordResponse.builder()
                    .message("Token格式错误")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的Token: {}", e.getMessage());
            ChangePasswordResponse response = ChangePasswordResponse.builder()
                    .message("Token不支持")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Token为空: {}", e.getMessage());
            ChangePasswordResponse response = ChangePasswordResponse.builder()
                    .message("Token为空")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("修改密码时发生未知错误: {}", e.getMessage(), e);
            ChangePasswordResponse response = ChangePasswordResponse.builder()
                    .message("修改密码失败: " + e.getMessage())
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * 删除用户接口
     * @param authorization
     * @param deleteUserDTO
     * @return
     */
    @DeleteMapping("/delete")
    @ApiOperation(value = "删除用户")
    public ResponseEntity<DeleteUserResponse> deleteUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody DeleteUserDTO deleteUserDTO) {
        log.info("删除用户：targetUser={}, Authorization={}", deleteUserDTO.getTargetUser(), authorization);
        
        // 从Authorization header中提取token (Bearer token格式)
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
            log.info("提取的token: {}", token);
        }
        
        // 如果没有提供token
        if (token == null || token.isEmpty()) {
            log.warn("未提供token或token为空");
            DeleteUserResponse response = DeleteUserResponse.builder()
                    .message("Token is invalid")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
        
        try {
            // 尝试解析token
            log.info("正在使用密钥解析token: {}", jwtProperties.getUserSecretKey());
            Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(), token);
            log.info("解析出的claims: {}", claims);
            
            // 获取当前用户信息
            Integer currentUserId = Integer.valueOf(claims.get(JwtClaimsConstant.USER_ID).toString());
            String currentUserRole = claims.get(JwtClaimsConstant.ROLE).toString();
            
            // 从数据库获取当前用户完整信息
            User currentUser = userService.getUserById(currentUserId);
            if (currentUser == null) {
                DeleteUserResponse response = DeleteUserResponse.builder()
                        .message("当前用户不存在")
                        .status(1)
                        .build();
                return ResponseEntity.badRequest().body(response);
            }
            
            // 设置当前用户角色
            currentUser.setRole(currentUserRole);
            
            // 调用服务删除用户
            boolean success = userService.deleteUser(deleteUserDTO.getTargetUser(), currentUser);
            
            if (success) {
                DeleteUserResponse response = DeleteUserResponse.builder()
                        .message("用户删除成功")
                        .status(0)
                        .build();
                return ResponseEntity.ok(response);
            } else {
                DeleteUserResponse response = DeleteUserResponse.builder()
                        .message("用户删除失败")
                        .status(1)
                        .build();
                return ResponseEntity.badRequest().body(response);
            }
        } catch (ExpiredJwtException e) {
            log.warn("Token已过期: {}", e.getMessage());
            DeleteUserResponse response = DeleteUserResponse.builder()
                    .message("Token已过期")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (SignatureException e) {
            log.warn("Token签名无效: {}", e.getMessage());
            DeleteUserResponse response = DeleteUserResponse.builder()
                    .message("Token无效")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (MalformedJwtException e) {
            log.warn("Token格式错误: {}", e.getMessage());
            DeleteUserResponse response = DeleteUserResponse.builder()
                    .message("Token格式错误")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的Token: {}", e.getMessage());
            DeleteUserResponse response = DeleteUserResponse.builder()
                    .message("Token不支持")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (IllegalArgumentException e) {
            log.warn("Token为空: {}", e.getMessage());
            DeleteUserResponse response = DeleteUserResponse.builder()
                    .message("Token为空")
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("删除用户时发生未知错误: {}", e.getMessage(), e);
            DeleteUserResponse response = DeleteUserResponse.builder()
                    .message("删除用户失败: " + e.getMessage())
                    .status(1)
                    .build();
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    /**
     * 获取指定农场的详细信息
     * @param farmId 农场ID
     * @return 农场详细信息
     */
    @GetMapping("/get-farm")
    @ApiOperation(value = "获取指定农场的详细信息")
    public Result<UserLoginResponseVO.FarmVO> getFarmDetails(
            @RequestParam Integer farmId) {
        log.info("获取农场详细信息: farmId={}", farmId);
        
        try {
            // 根据农场ID获取农场详细信息
            UserLoginResponseVO.FarmVO farm = farmMapper.getFarmById(farmId);
            if (farm == null) {
                return Result.error("未找到指定的农场");
            }
            
            // 获取农场组件
            List<String> components = farmMapper.getComponentsByFarmId(farmId);
            farm.setComponents(components);
            
            // 获取农场位置信息
            List<UserLoginResponseVO.FarmVO.LocationVO> locations = farmMapper.getLocationsByFarmId(farmId);
            farm.setLocations(locations);
            
            // 获取农场作物信息
            List<FarmCropVO> crops = farmMapper.getCropsByFarmId(farmId);
            farm.setCrops(crops);
            
            return Result.success(farm);
        } catch (Exception e) {
            log.error("获取农场详细信息失败: {}", e.getMessage(), e);
            return Result.error("获取农场详细信息失败: " + e.getMessage());
        }
    }
}