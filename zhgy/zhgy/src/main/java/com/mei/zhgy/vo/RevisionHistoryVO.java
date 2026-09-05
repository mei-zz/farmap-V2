package com.mei.zhgy.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevisionHistoryVO {
    private String revisionId;      // 修改记录ID
    private Long expertId;          // 专家ID
    private LocalDateTime revisionTime;  // 修改时间
    private Integer isAgree;        // 是否同意上一次修改：1-同意；0-不同意；null-首次修改
    private String revisionNotes;   // 修改理由
}