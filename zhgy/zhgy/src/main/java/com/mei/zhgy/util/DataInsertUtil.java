package com.mei.zhgy.util;

import org.springframework.stereotype.Component;

import com.mei.zhgy.entity.AccumulatedTemperature;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
public class DataInsertUtil {
    
    private static final String DB_URL = "jdbc:mysql://localhost:3307/zhgy?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
    private static final String DB_USER = "mei";
    private static final String DB_PASSWORD = "mei123456";
    
    private static final Random random = new Random();
    
    public static void main(String[] args) {
        try {
            // 注册MySQL驱动
            Class.forName("com.mysql.cj.jdbc.Driver");
            
            // 连接数据库
            Connection connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            
            // 清空现有数据（可选）
            // clearExistingData(connection);
            
            // 生成并插入数据
            insertAccumulatedTemperatureData(connection);
            
            // 关闭连接
            connection.close();
            
            System.out.println("数据插入完成");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 生成并插入积温数据
     */
    private static void insertAccumulatedTemperatureData(Connection connection) throws SQLException {
        // 获取当前年份
        int currentYear = LocalDate.now().getYear();
        int lastYear = currentYear - 1;
        
        // 去年整年的开始和结束日期
        LocalDate lastYearStartDate = LocalDate.of(lastYear, 1, 1);
        LocalDate lastYearEndDate = LocalDate.of(lastYear, 12, 31);
        
        // 今年至今的开始日期和结束日期
        LocalDate thisYearStartDate = LocalDate.of(currentYear, 1, 1);
        LocalDate thisYearEndDate = LocalDate.now();
        
        // 生成去年整年的积温数据
        System.out.println("开始生成" + lastYear + "年1月1日至" + lastYear + "年12月31日的积温数据");
        List<AccumulatedTemperature> lastYearData = generateData(1, lastYearStartDate, lastYearEndDate);
        
        // 生成今年至今的积温数据
        System.out.println("开始生成" + currentYear + "年1月1日至" + thisYearEndDate + "年的积温数据");
        List<AccumulatedTemperature> thisYearData = generateData(1, thisYearStartDate, thisYearEndDate);
        
        // 合并数据
        lastYearData.addAll(thisYearData);
        
        // 插入数据
        System.out.println("开始插入" + lastYearData.size() + "条积温数据到数据库");
        insertData(connection, lastYearData);
        
        System.out.println("成功插入" + lastYearData.size() + "条积温数据");
    }
    
    /**
     * 生成指定日期范围内的模拟积温数据
     */
    private static List<AccumulatedTemperature> generateData(int farmId, LocalDate startDate, LocalDate endDate) {
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
    
    /**
     * 插入数据到数据库
     */
    private static void insertData(Connection connection, List<AccumulatedTemperature> temperatures) throws SQLException {
        String sql = "INSERT INTO accumulated_temperature (farm_id, date, temperature, accumulated_temp) VALUES (?, ?, ?, ?)";
        
        PreparedStatement statement = connection.prepareStatement(sql);
        
        for (AccumulatedTemperature temp : temperatures) {
            statement.setInt(1, temp.getFarmId());
            statement.setDate(2, Date.valueOf(temp.getDate()));
            statement.setBigDecimal(3, temp.getTemperature());
            statement.setBigDecimal(4, temp.getAccumulatedTemp());
            statement.addBatch();
        }
        
        statement.executeBatch();
        statement.close();
    }
    
    /**
     * 清空现有数据（谨慎使用）
     */
    private static void clearExistingData(Connection connection) throws SQLException {
        String sql = "DELETE FROM accumulated_temperature";
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.execute();
        statement.close();
        System.out.println("已清空accumulated_temperature表中的现有数据");
    }
}