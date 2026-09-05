package com.mei.zhgy.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Model implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer id;
    private Integer farmId;
    private Integer modelOrder;
    private String name;
    private String url;
    private String reqType;  // 请求类型：text/figure/hybrid
    private String resType;  // 响应类型：text/figure/hybrid
}