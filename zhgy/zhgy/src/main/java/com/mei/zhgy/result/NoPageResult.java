package com.mei.zhgy.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NoPageResult  implements Serializable {
    private List records; //当前数据集合
}
