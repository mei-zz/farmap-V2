package com.mei.zhgy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.entity.AccumulatedTemperature;
import com.mei.zhgy.vo.GaodeWeatherResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
@Slf4j
public class WeatherApiService {
    
    // 高德地图API Key (实际应用中应该从配置文件读取)
    @Value("${gaode.api.key:your_api_key_here}")
    private String gaodeApiKey;
    
    private final Random random = new Random();
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 调用高德地图API获取当前天气数据
     * 
     * @param adcode 城市编码
     * @return 当前天气数据
     */
    public GaodeWeatherResponse getCurrentWeather(String adcode) {
        try {
            String url = String.format(
                "https://restapi.amap.com/v3/weather/weatherInfo?key=%s&city=%s&extensions=base",
                gaodeApiKey, URLEncoder.encode(adcode, StandardCharsets.UTF_8.toString())
            );
            
            String response = restTemplate.getForObject(url, String.class);
            
            if (response != null) {
                return objectMapper.readValue(response, GaodeWeatherResponse.class);
            } else {
                log.error("获取天气数据失败，响应为空");
                return null;
            }
        } catch (Exception e) {
            log.error("调用高德地图天气API失败", e);
            return null;
        }
    }
    
    /**
     * 调用高德地图API获取预报天气数据
     * 
     * @param adcode 城市编码
     * @return 预报天气数据
     */
    public GaodeWeatherResponse getForecastWeather(String adcode) {
        try {
            String url = String.format(
                "https://restapi.amap.com/v3/weather/weatherInfo?key=%s&city=%s&extensions=all",
                gaodeApiKey, URLEncoder.encode(adcode, StandardCharsets.UTF_8.toString())
            );
            
            String response = restTemplate.getForObject(url, String.class);
            
            if (response != null) {
                return objectMapper.readValue(response, GaodeWeatherResponse.class);
            } else {
                log.error("获取天气预报数据失败，响应为空");
                return null;
            }
        } catch (Exception e) {
            log.error("调用高德地图天气预报API失败", e);
            return null;
        }
    }
    
    /**
     * 获取历史天气数据（由于高德地图API不提供历史天气数据，这里仍然使用模拟数据）
     * 实际应用中，如果需要历史天气数据，需要使用其他提供历史数据的API
     * 
     * @param latitude 纬度
     * @param longitude 经度
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 天气数据列表
     */
    public List<AccumulatedTemperature> getHistoricalWeatherData(
            double latitude, double longitude, LocalDate startDate, LocalDate endDate) {
        
        // 实际应用中，这里应该调用真实的天气API，例如：
        // https://api.openweathermap.org/data/2.5/onecall/timemachine
        // 或其他提供历史天气数据的API
        
        // 为了演示，我们生成一些模拟数据
        List<AccumulatedTemperature> temperatures = new ArrayList<>();
        LocalDate currentDate = startDate;
        int id = 1;
        
        // 根据纬度和日期生成合理的温度数据
        while (!currentDate.isAfter(endDate)) {
            // 生成模拟温度数据（根据日期和地理位置生成合理的温度）
            double baseTemp = calculateBaseTemperature(latitude, currentDate);
            double temperature = baseTemp + (random.nextDouble() * 10 - 5); // -5到+5度的随机变化
            
            AccumulatedTemperature accTemp = AccumulatedTemperature.builder()
                    .id(id++)
                    .farmId(1) // 示例农场ID
                    .date(currentDate)
                    .temperature(BigDecimal.valueOf(temperature))
                    .accumulatedTemp(BigDecimal.ZERO) // 初始累积温度为0，后续会计算
                    .build();
            
            temperatures.add(accTemp);
            currentDate = currentDate.plusDays(1);
        }
        
        // 计算累积温度
        for (int i = 1; i < temperatures.size(); i++) {
            temperatures.get(i).setAccumulatedTemp(
                temperatures.get(i-1).getAccumulatedTemp().add(temperatures.get(i).getTemperature())
            );
        }
        
        return temperatures;
    }
    
    /**
     * 根据纬度和日期计算基础温度
     * @param latitude 纬度
     * @param date 日期
     * @return 基础温度
     */
    private double calculateBaseTemperature(double latitude, LocalDate date) {
        // 简单的季节性温度变化模型
        int dayOfYear = date.getDayOfYear();
        
        // 根据纬度调整基础温度
        double latitudeFactor = 20.0 - Math.abs(latitude) * 0.3;
        
        // 季节性变化（以夏至为基准）
        double seasonalFactor = Math.sin(2 * Math.PI * (dayOfYear - 172) / 365.0);
        double tempVariation = 15.0 * seasonalFactor;
        
        return latitudeFactor + tempVariation;
    }
}