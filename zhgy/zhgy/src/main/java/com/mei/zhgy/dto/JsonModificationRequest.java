package com.mei.zhgy.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
@ApiModel(description = "JSON修改请求DTO")
public class JsonModificationRequest implements Serializable {
    
    @ApiModelProperty(value = "需要修改的JSON数据", required = true)
    private Object jsonData;
    
    @ApiModelProperty(value = "用于修改JSON的文本描述", required = true)
    private String modificationText;
}