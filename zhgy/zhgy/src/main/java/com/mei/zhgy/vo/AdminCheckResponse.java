package com.mei.zhgy.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminCheckResponse {
    private String message;
    private Integer status; // 0: admin check correct, 1: not admin, 2: token invalid
}