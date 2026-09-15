package com.mei.zhgy.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String requestId;
    private String userId;
    private String imageUrls;
    private LocalDateTime uploadTime;
    private Integer imageCount;
    private Integer status; // 0-待处理，1-已生成初始结果，2-专家修正中，3-已达成共识
}