package com.mei.zhgy.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.io.Serializable;

@Data
public class AIModelRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private MultipartFile[] images; // 1-5张图片
}