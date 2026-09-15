package com.mei.zhgy.dto;

import io.swagger.annotations.ApiModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ApiModel(description = "员工登录时传递的数据模型")
public class UserLoginDTO implements Serializable {

    private String username;
    private String password;
}
