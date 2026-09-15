package com.mei.zhgy.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Farm implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Integer id;
    private String name;
    private String type;
    private String address;
    private Integer zoom;
    private String center;
    private String username;
    private String adcode; // 城市编码
}