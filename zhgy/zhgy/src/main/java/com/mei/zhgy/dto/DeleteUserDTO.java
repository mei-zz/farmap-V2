package com.mei.zhgy.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class DeleteUserDTO implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String targetUser;
}