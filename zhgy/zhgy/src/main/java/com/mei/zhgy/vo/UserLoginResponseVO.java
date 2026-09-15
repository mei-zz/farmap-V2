package com.mei.zhgy.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginResponseVO implements Serializable {
    private UserVO user;
    private List<FarmVO> farms;
    private String token;
    private Integer status;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserVO {
        private String name;
        private String role;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FarmVO {
        private Integer id;
        private String name;
        private String type;
        private String address;
        private Integer zoom;
        private String center;
        private String username;
        private Integer userId;
        private List<String> components;
        
        private List<LocationVO> locations;
        private List<FarmCropVO> crops;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class LocationVO {
            private Integer id;
            private Double longitude;
            private Double latitude;
        }
    }
}