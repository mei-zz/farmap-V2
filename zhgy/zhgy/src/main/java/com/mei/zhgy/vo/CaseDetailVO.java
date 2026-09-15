package com.mei.zhgy.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseDetailVO {
    // 用户请求信息
    private UserRequestInfo userRequestInfo;
    
    // 初始结果信息
    private InitialResultInfo initialResultInfo;
    
    // 专家修改记录列表
    private List<RevisionRecordVO> revisionRecords;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserRequestInfo {
        private String requestId;
        private String imageUrls; // 图片存储路径，多图用逗号分隔
        private LocalDateTime uploadTime;
        private Integer status; // 确认案例状态为可修改
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InitialResultInfo {
        private String jsonData; // 模型生成的初始 JSON
        private String modelVersion; // 模型版本，供专家参考
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RevisionRecordVO {
        private String revisionId; // 修改记录 ID
        private Long expertId; // 修改专家
        private String revisedJson; // 修改后的 JSON
        private LocalDateTime revisionTime; // 修改时间
        private Boolean isAgree; // 是否同意前序修改
        private String revisionNotes; // 修改理由
    }
}