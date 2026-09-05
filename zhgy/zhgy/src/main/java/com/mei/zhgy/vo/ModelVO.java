package com.mei.zhgy.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelVO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String message;
    private Integer status;
    private List<ModelItem> models;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelItem implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private Integer id;
        private String name;
        private Integer order;
        private String reqType;  // 请求类型：text/figure/hybrid
        private String resType;  // 响应类型：text/figure/hybrid
        private String url;
    }
}