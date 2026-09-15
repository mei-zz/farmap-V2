package com.mei.zhgy.dto;

import io.swagger.annotations.ApiModel;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ApiModel(description = "用户注册请求数据模型")
public class SignupRequest implements Serializable {
    private String name;
    private String password;
}