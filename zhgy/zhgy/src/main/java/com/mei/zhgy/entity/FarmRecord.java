package com.mei.zhgy.entity;

import io.swagger.models.auth.In;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.type.Alias;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FarmRecord  implements Serializable {
    private static final long serialVersionUID = 1L;

    private String date;
    private String farm_addr;
    private String username;
    private String record_img;
}
