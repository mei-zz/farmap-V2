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
public class InitialResult implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String resultId;
    private String requestId;
    private String jsonData;
    private LocalDateTime generateTime;
    private String modelVersion;
    private Float confidence;
}