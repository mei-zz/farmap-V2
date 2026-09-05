package com.mei.zhgy.dto;

import io.swagger.annotations.ApiModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ApiModel(description = "用户注册时传递的数据模型")
public class UserRegisterDTO implements Serializable {
    private String username;
    private String password;
    private String role; // 用户角色：admin、guest或expert
}