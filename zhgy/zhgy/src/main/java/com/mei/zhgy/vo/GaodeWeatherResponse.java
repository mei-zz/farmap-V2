package com.mei.zhgy.vo;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GaodeWeatherResponse {
    private String status;
    private String count;
    private String info;
    private String infocode;
    private List<LiveWeather> lives;
    /** Gaode's forecast response uses the plural JSON key "forecasts". */
    @JsonProperty("forecasts")
    private List<ForecastWeather> forecast;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
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
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ForecastWeather {
        private String city;
        private String adcode;
        private String province;
        private String reporttime;
        private List<Forecast> casts;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
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
