package com.mei.zhgy.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ExpertRevision {
    private String revisionId;      // 修改记录ID
    private String requestId;       // 案例ID
    private Long expertId;          // 专家ID
    private String revisedJson;     // 修改后的JSON
    private LocalDateTime revisionTime; // 修改时间
    private Integer isAgree;        // 是否同意上一次修改
    private String revisionNotes;   // 修改理由
}