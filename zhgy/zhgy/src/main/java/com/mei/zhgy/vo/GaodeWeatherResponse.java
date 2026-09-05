package com.mei.zhgy.vo;

import lombok.Data;
import java.util.List;

@Data
public class GaodeWeatherResponse {
    private String status;
    private String count;
    private String info;
    private String infocode;
    private List<LiveWeather> lives;
    private List<ForecastWeather> forecast;
    
    @Data
    public static class LiveWeather {
        private String province;
        private String city;
        private String adcode;
        private String weather;
        private String temperature;
        private String winddirection;
        private String windpower;
        private String humidity;
        private String reporttime;
    }
    
    @Data
    public static class ForecastWeather {
        private String city;
        private String adcode;
        private String province;
        private String reporttime;
        private List<Forecast> casts;
    }
    
    @Data
    public static class Forecast {
        private String date;
        private String week;
        private String dayweather;
        private String nightweather;
        private String daytemp;
        private String nighttemp;
        private String daywind;
        private String nightwind;
        private String daypower;
        private String nightpower;
    }
}