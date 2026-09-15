package com.mei.zhgy.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 请求数据修改前后对比VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestDataComparisonVO {
    private String requestId;                           // 请求ID
    private String initialJson;                         // 初始JSON数据（修改前）
    private List<ExpertRevisionData> revisionDataList;  // 专家修改数据列表（修改后）
    private LocalDateTime initialGenerateTime;          // 初始数据生成时间
    private Integer revisionCount;                      // 修改次数

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExpertRevisionData {
        private String revisionId;          // 修改记录ID
        private Long expertId;              // 专家ID
        private String revisedJson;         // 修改后的JSON
        private LocalDateTime revisionTime; // 修改时间
        private Integer isAgree;            // 是否同意前序修改
        private String revisionNotes;       // 修改理由
    }
}