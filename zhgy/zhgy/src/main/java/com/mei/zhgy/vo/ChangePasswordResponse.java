package com.mei.zhgy.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChangePasswordResponse {
    private String message;
    private Integer status; // 0: 成功, 1: 失败
}