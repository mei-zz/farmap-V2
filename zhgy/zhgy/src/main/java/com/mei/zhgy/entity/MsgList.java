package com.mei.zhgy.entity;

import io.swagger.models.auth.In;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MsgList  implements Serializable {
    private Integer id;
    private Integer pestNum;
    private String orchAddr;
    private String noteTime;
    private String fruitRetentionMsg;
    private String pestMsg;
}
