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
public class FarmLocation implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer id;
    private Double longitude;
    private Double latitude;
}