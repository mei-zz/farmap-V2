package com.mei.zhgy.scheduled;

import com.mei.zhgy.entity.AccumulatedTemperature;
import com.mei.zhgy.service.AccumulatedTemperatureService;
import com.mei.zhgy.service.WeatherApiService;
import com.mei.zhgy.vo.GaodeWeatherResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
@Slf4j
public class WeatherDataScheduledTask {
    
    @Autowired
    private WeatherApiService weatherApiService;
    
    @Autowired
    private AccumulatedTemperatureService accumulatedTemperatureService;
    
    // 城市编码 (实际应用中应该从数据库获取农场对应的城市编码)
    @Value("${gaode.city.adcode:110101}")
    private String cityAdcode;
    
    /**
     * 定时任务：每天凌晨2点执行，获取昨天的天气数据
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void fetchWeatherData() {
        try {
            log.info("开始执行天气数据获取任务");
            
            // 获取昨天的日期
            LocalDate yesterday = LocalDate.now().minusDays(1);
            
            // 调用高德地图API获取当前天气数据
            GaodeWeatherResponse weatherResponse = weatherApiService.getCurrentWeather(cityAdcode);
            
            if (weatherResponse != null && "1".equals(weatherResponse.getStatus()) && 
                weatherResponse.getLives() != null && !weatherResponse.getLives().isEmpty()) {
                
                GaodeWeatherResponse.LiveWeather liveWeather = weatherResponse.getLives().get(0);
                
                // 解析温度数据
                BigDecimal temperature = new BigDecimal(liveWeather.getTemperature());
                
                // 创建积温记录
                AccumulatedTemperature temp = AccumulatedTemperature.builder()
                        .farmId(1) // 示例农场ID，实际应用中应从数据库获取
                        .date(yesterday)
                        .temperature(temperature)
                        .accumulatedTemp(BigDecimal.ZERO) // 初始累积温度为0，后续会计算
                        .build();
                
                // 保存到数据库
                accumulatedTemperatureService.save(temp);
                log.info("成功保存天气数据: 日期={}, 温度={}°C", yesterday, temperature);
            } else {
                log.warn("获取天气数据失败或返回数据为空");
            }
        } catch (Exception e) {
            log.error("执行天气数据获取任务失败", e);
        }
    }
    
    /**
     * 定时任务：每年1月1日凌晨3点执行，获取去年整年和今年至今的积温数据
     */
    @Scheduled(cron = "0 0 3 1 1 ?")
    public void fetchAnnualAccumulatedTemperatureData() {
        try {
            log.info("开始执行年度积温数据获取任务");
            
            // 获取当前年份
            int currentYear = LocalDate.now().getYear();
            int lastYear = currentYear - 1;
            
            // 去年整年的开始和结束日期
            LocalDate lastYearStartDate = LocalDate.of(lastYear, 1, 1);
            LocalDate lastYearEndDate = LocalDate.of(lastYear, 12, 31);
            
            // 今年至今的开始日期和结束日期
            LocalDate thisYearStartDate = LocalDate.of(currentYear, 1, 1);
            LocalDate thisYearEndDate = LocalDate.now();
            
            // 示例经纬度（实际应用中应从农场表中获取）
            double latitude = 30.0;   // 示例纬度
            double longitude = 120.0; // 示例经度
            
            // 调用天气API获取去年整年的天气数据
            List<AccumulatedTemperature> lastYearTemperatures = weatherApiService.getHistoricalWeatherData(
                    latitude, longitude, lastYearStartDate, lastYearEndDate);
            
            // 调用天气API获取今年至今的天气数据
            List<AccumulatedTemperature> thisYearTemperatures = weatherApiService.getHistoricalWeatherData(
                    latitude, longitude, thisYearStartDate, thisYearEndDate);
            
            // 合并两年的数据
            lastYearTemperatures.addAll(thisYearTemperatures);
            
            // 计算累积温度（基于生长温度阈值10度）
            BigDecimal growthTempThreshold = new BigDecimal("10");
            BigDecimal accumulatedTemp = BigDecimal.ZERO;
            
            for (AccumulatedTemperature temp : lastYearTemperatures) {
                // 计算每日积温（当日平均温度 - 生长温度阈值，如果结果为负则为0）
                BigDecimal dailyAccumulated = temp.getTemperature().subtract(growthTempThreshold);
                if (dailyAccumulated.compareTo(BigDecimal.ZERO) < 0) {
                    dailyAccumulated = BigDecimal.ZERO;
                }
                
                // 累加到总积温
                accumulatedTemp = accumulatedTemp.add(dailyAccumulated);
                temp.setAccumulatedTemp(accumulatedTemp);
                
                // 设置农场ID（实际应用中应根据经纬度匹配农场）
                temp.setFarmId(1);
            }
            
            // 批量保存到数据库
            if (!lastYearTemperatures.isEmpty()) {
                accumulatedTemperatureService.batchSave(lastYearTemperatures);
                log.info("成功保存{}条年度积温数据", lastYearTemperatures.size());
            } else {
                log.info("没有获取到年度积温数据");
            }
        } catch (Exception e) {
            log.error("执行年度积温数据获取任务失败", e);
        }
    }
}