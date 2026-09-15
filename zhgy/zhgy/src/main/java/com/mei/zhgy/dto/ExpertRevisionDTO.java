package com.mei.zhgy.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
@ApiModel("专家修改请求DTO")
public class ExpertRevisionDTO {
    
    @ApiModelProperty("修改后的完整JSON")
    private String revisedJson;
    
    @ApiModelProperty("是否同意上一次修改：1-同意；0-不同意；null-首次修改")
    private Integer isAgree;
    
    @ApiModelProperty("修改理由")
    private String revisionNotes;
}