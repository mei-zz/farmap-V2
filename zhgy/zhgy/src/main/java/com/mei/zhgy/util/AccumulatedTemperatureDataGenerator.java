package com.mei.zhgy.util;

import com.mei.zhgy.entity.AccumulatedTemperature;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AccumulatedTemperatureDataGenerator {
    
    private static final Random random = new Random();
    
    /**
     * 生成指定日期范围内的模拟积温数据
     * @param farmId 农场ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 积温数据列表
     */
    public static List<AccumulatedTemperature> generateData(int farmId, LocalDate startDate, LocalDate endDate) {
        List<AccumulatedTemperature> temperatures = new ArrayList<>();
        LocalDate currentDate = startDate;
        BigDecimal accumulatedTemp = BigDecimal.ZERO;
        
        while (!currentDate.isAfter(endDate)) {
            // 生成模拟的日平均温度（根据季节变化）
            double baseTemp = calculateBaseTemperature(currentDate);
            double dailyTemp = baseTemp + (random.nextDouble() * 10 - 5); // -5到+5度的随机变化
            
            // 计算积温（当日平均温度 - 生长温度阈值10度，如果结果为负则为0）
            double dailyAccumulated = dailyTemp - 10.0;
            if (dailyAccumulated < 0) {
                dailyAccumulated = 0;
            }
            
            // 累加到总积温
            accumulatedTemp = accumulatedTemp.add(BigDecimal.valueOf(dailyAccumulated));
            
            AccumulatedTemperature accTemp = AccumulatedTemperature.builder()
                    .farmId(farmId)
                    .date(currentDate)
                    .temperature(BigDecimal.valueOf(dailyTemp))
                    .accumulatedTemp(accumulatedTemp)
                    .build();
            
            temperatures.add(accTemp);
            currentDate = currentDate.plusDays(1);
        }
        
        return temperatures;
    }
    
    /**
     * 根据日期计算基础温度（模拟季节变化）
     * @param date 日期
     * @return 基础温度
     */
    private static double calculateBaseTemperature(LocalDate date) {
        int dayOfYear = date.getDayOfYear();
        
        // 简单的季节性温度变化模型（以夏至为最热）
        double seasonalFactor = Math.sin(2 * Math.PI * (dayOfYear - 172) / 365.0);
        double tempVariation = 15.0 * seasonalFactor;
        
        // 基础温度
        double baseTemp = 15.0;
        
        return baseTemp + tempVariation;
    }
}