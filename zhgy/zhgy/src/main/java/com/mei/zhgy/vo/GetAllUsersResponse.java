package com.mei.zhgy.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GetAllUsersResponse {
    private String message;
    private List<User> users;
    
    @Data
    @Builder
    public static class User {
        private Integer id;
        private String name;
        private String role;
    }
}