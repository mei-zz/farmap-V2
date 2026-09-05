package com.mei.zhgy.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ImageAnalyzeDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private List<String> imageUrls; // 图片URL列表，支持1-5张图片
}