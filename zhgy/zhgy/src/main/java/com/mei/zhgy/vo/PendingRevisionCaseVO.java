package com.mei.zhgy.vo;

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
public class PendingRevisionCaseVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String requestId;        // 案例ID
    private LocalDateTime uploadTime; // 用户上传时间
    private Integer imageCount;      // 图片数量
    private LocalDateTime generateTime; // 初始JSON生成时间
    private Integer revisionCount;   // 已修改的专家数量
}