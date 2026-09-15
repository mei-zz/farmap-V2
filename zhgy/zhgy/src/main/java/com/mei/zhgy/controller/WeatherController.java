package com.mei.zhgy.controller;

import com.mei.zhgy.entity.AccumulatedTemperature;
import com.mei.zhgy.entity.Weather;
import com.mei.zhgy.mapper.FarmMapper;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.AccumulatedTemperatureService;
import com.mei.zhgy.service.WeatherService;
import com.mei.zhgy.vo.AccumulatedTemperatureVO;
import com.mei.zhgy.vo.WeatherIntroResponseVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@Slf4j
@RequestMapping("/weather")
@Api(tags = "天气相关接口")
public class WeatherController {
    
    @Autowired
    private WeatherService weatherService;
    
    @Autowired
    private AccumulatedTemperatureService accumulatedTemperatureService;
    
    @Autowired
    private FarmMapper farmMapper;
    
    /**
     * 获取天气介绍信息
     * @param farmId 农场ID（可选）
     * @param farmType 农场类型（可选，如果未提供farmId则必须提供）
     * @return
     */
    @GetMapping("/intro")
    @ApiOperation(value = "获取天气介绍信息")
    public Result<WeatherIntroResponseVO> getWeatherIntro(
            @RequestParam(required = false) Integer farmId,
            @RequestParam(required = false) String farmType) {
        
        log.info("获取天气介绍信息：农场ID={}, 农场类型={}", farmId, farmType);
        
        try {
            // 如果没有提供farmId和farmType，则返回错误
            if (farmId == null && farmType == null) {
                return Result.error("请提供farmId或farmType参数");
            }
            
            // 如果提供了farmId，则优先使用farmId获取农场类型
            String typeToUse = farmType;
            if (farmId != null) {
                // 获取农场类型
                typeToUse = farmMapper.getFarmTypeById(farmId);
            }
            
            if (typeToUse == null || typeToUse.isEmpty()) {
                return Result.error("无法确定农场类型");
            }
            
            // 获取12个月的天气介绍信息
            List<List<WeatherIntroResponseVO.WeatherIntroItem>> intro = new ArrayList<>();
            for (int month = 0; month < 12; month++) {
                // 获取指定月份的天气介绍
                List<Weather> weatherList = weatherService.getByFarmIdAndMonth(farmId, month);
                
                // 如果没有获取到自定义数据，则使用默认数据
                if (weatherList.isEmpty()) {
                    weatherList = weatherService.getByFarmTypeAndMonth(typeToUse, month);
                }
                
                // 转换为响应VO
                List<WeatherIntroResponseVO.WeatherIntroItem> monthItems = new ArrayList<>();
                for (Weather weather : weatherList) {
                    WeatherIntroResponseVO.WeatherIntroItem item = WeatherIntroResponseVO.WeatherIntroItem.builder()
                            .id(weather.getId())
                            .text(weather.getText())
                            .build();
                    monthItems.add(item);
                }
                
                intro.add(monthItems);
            }
            
            WeatherIntroResponseVO responseVO = WeatherIntroResponseVO.builder()
                    .intro(intro)
                    .build();
            
            return Result.success(responseVO);
        } catch (Exception e) {
            log.error("获取天气介绍信息失败", e);
            return Result.error("获取天气介绍信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取积温数据
     * @param farmType 农场类型
     * @return
     */
    @GetMapping("/accumulated-temperature")
    @ApiOperation(value = "获取积温数据")
    public Result<AccumulatedTemperatureVO> getAccumulatedTemperature(
            @RequestParam String farmType) {
        
        log.info("获取积温数据：农场类型={}", farmType);
        
        try {
            // 固定返回farm_id为1的积温数据
            Integer farmId = 1;
            
            // 获取农场积温数据
            AccumulatedTemperatureVO farmTemperatureData = getFarmAccumulatedTemperature(farmId);
            
            return Result.success(farmTemperatureData);
        } catch (Exception e) {
            log.error("获取积温数据失败", e);
            return Result.error("获取积温数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取单个农场的积温数据
     * @param farmId 农场ID
     * @return 积温数据
     */
    private AccumulatedTemperatureVO getFarmAccumulatedTemperature(Integer farmId) {
        // 生长温度阈值，默认为10度
        BigDecimal growthTempThreshold = new BigDecimal("10");
        
        // 获取当前年份
        int currentYear = LocalDate.now().getYear();
        int lastYear = currentYear - 1;
        
        // 获取去年整年的积温数据
        List<AccumulatedTemperature> lastYearData = accumulatedTemperatureService.getByFarmIdAndYear(farmId, lastYear);
        
        // 计算去年的积温（基于生长温度阈值）
        List<AccumulatedTemperatureVO.TemperatureItem> lastYearItems = new ArrayList<>();
        for (AccumulatedTemperature temp : lastYearData) {
            // 计算积温（当日平均温度 - 生长温度阈值，如果结果为负则为0）
            BigDecimal dailyAccumulated = temp.getTemperature().subtract(growthTempThreshold);
            if (dailyAccumulated.compareTo(BigDecimal.ZERO) < 0) {
                dailyAccumulated = BigDecimal.ZERO;
            }
            
            // 累加到总积温
            BigDecimal accumulatedTemp = lastYearItems.isEmpty() ? 
                    dailyAccumulated : 
                    lastYearItems.get(lastYearItems.size() - 1).getAccTemp().add(dailyAccumulated);
            
            // 构建返回项
            AccumulatedTemperatureVO.TemperatureItem item = AccumulatedTemperatureVO.TemperatureItem.builder()
                    .id(temp.getId())
                    .date(temp.getDate())
                    .accTemp(accumulatedTemp)
                    .build();
            
            lastYearItems.add(item);
        }
        
        // 获取今年至今的积温数据（从1月1日到今天）
        List<AccumulatedTemperature> thisYearData = accumulatedTemperatureService.getByFarmIdAndYear(farmId, currentYear);
        
        // 计算今年的积温（基于生长温度阈值）
        List<AccumulatedTemperatureVO.TemperatureItem> thisYearItems = new ArrayList<>();
        for (AccumulatedTemperature temp : thisYearData) {
            // 计算积温（当日平均温度 - 生长温度阈值，如果结果为负则为0）
            BigDecimal dailyAccumulated = temp.getTemperature().subtract(growthTempThreshold);
            if (dailyAccumulated.compareTo(BigDecimal.ZERO) < 0) {
                dailyAccumulated = BigDecimal.ZERO;
            }
            
            // 累加到总积温
            BigDecimal accumulatedTemp = thisYearItems.isEmpty() ? 
                    dailyAccumulated : 
                    thisYearItems.get(thisYearItems.size() - 1).getAccTemp().add(dailyAccumulated);
            
            // 构建返回项
            AccumulatedTemperatureVO.TemperatureItem item = AccumulatedTemperatureVO.TemperatureItem.builder()
                    .id(temp.getId())
                    .date(temp.getDate())
                    .accTemp(accumulatedTemp)
                    .build();
            
            thisYearItems.add(item);
        }
        
        // 构建最终返回结果
        return AccumulatedTemperatureVO.builder()
                .last(lastYearItems)
                .thisYear(thisYearItems)
                .build();
    }
}