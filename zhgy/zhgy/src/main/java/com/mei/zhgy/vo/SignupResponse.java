package com.mei.zhgy.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SignupResponse {
    private String message;
    private User user;
    
    @Data
    @Builder
    public static class User {
        private String name;
        private String role;
        private String token;
    }
}