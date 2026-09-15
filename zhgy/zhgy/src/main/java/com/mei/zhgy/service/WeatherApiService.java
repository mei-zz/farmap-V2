package com.mei.zhgy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.mei.zhgy.entity.AccumulatedTemperature;
import com.mei.zhgy.vo.WeatherForecastDay;
import com.mei.zhgy.vo.GaodeWeatherResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

@Service
@Slf4j
public class WeatherApiService {
    
    // Optional legacy provider. The free Open-Meteo path below is the local
    // default so the app works without putting a provider key in the frontend.
    @Value("${gaode.api.key:}")
    private String gaodeApiKey;

    @Value("${open-meteo.api.enabled:true}")
    private boolean openMeteoEnabled;

    @Value("${open-meteo.api.base-url:https://api.open-meteo.com/v1/forecast}")
    private String openMeteoBaseUrl;

    @Value("${open-meteo.api.key:}")
    private String openMeteoApiKey;

    @Value("${open-meteo.api.connect-timeout-ms:5000}")
    private int openMeteoConnectTimeoutMs;

    @Value("${open-meteo.api.read-timeout-ms:10000}")
    private int openMeteoReadTimeoutMs;
    
    private final Random random = new Random();
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    private void configureRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(openMeteoConnectTimeoutMs);
        factory.setReadTimeout(openMeteoReadTimeoutMs);
        restTemplate.setRequestFactory(factory);
    }

    public boolean isConfigured() {
        return isGaodeConfigured() || openMeteoEnabled;
    }

    private boolean isGaodeConfigured() {
        return gaodeApiKey != null && !gaodeApiKey.isBlank() && !"your_api_key_here".equals(gaodeApiKey);
    }

    /** Resolves a provider city code from the authoritative farm center. */
    public String resolveAdcode(double longitude, double latitude) {
        if (!isGaodeConfigured()) return null;
        try {
            String url = String.format(
                    "https://restapi.amap.com/v3/geocode/regeo?key=%s&location=%s,%s&extensions=base",
                    gaodeApiKey, longitude, latitude);
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) return null;
            JsonNode root = objectMapper.readTree(response);
            if (!"1".equals(root.path("status").asText())) return null;
            String adcode = root.path("regeocode").path("addressComponent").path("adcode").asText();
            return adcode == null || adcode.isBlank() ? null : adcode;
        } catch (Exception e) {
            log.warn("高德逆地理编码不可用: {}", e.getClass().getSimpleName());
            return null;
        }
    }
    
    /**
     * 调用高德地图API获取当前天气数据
     * 
     * @param adcode 城市编码
     * @return 当前天气数据
     */
    public GaodeWeatherResponse getCurrentWeather(String adcode) {
        if (!isGaodeConfigured()) return null;
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
        if (!isGaodeConfigured()) return null;
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
     * Fetches one authoritative farm weather snapshot. Configured Gaode is
     * preferred for existing deployments; Open-Meteo is the keyless fallback.
     */
    public WeatherSnapshot getWeather(double longitude, double latitude) {
        if (isGaodeConfigured()) {
            String adcode = resolveAdcode(longitude, latitude);
            if (adcode != null && !adcode.isBlank()) {
                GaodeWeatherResponse live = getCurrentWeather(adcode);
                GaodeWeatherResponse forecast = getForecastWeather(adcode);
                WeatherSnapshot gaode = fromGaode(live, forecast);
                if (gaode != null) return gaode;
            }
        }
        return openMeteoEnabled ? getOpenMeteoWeather(longitude, latitude) : null;
    }

    private WeatherSnapshot fromGaode(GaodeWeatherResponse liveResponse, GaodeWeatherResponse forecastResponse) {
        if (liveResponse == null || !"1".equals(liveResponse.getStatus())
                || liveResponse.getLives() == null || liveResponse.getLives().isEmpty()) return null;
        GaodeWeatherResponse.LiveWeather live = liveResponse.getLives().get(0);
        List<WeatherForecastDay> days = new ArrayList<>();
        if (forecastResponse != null && forecastResponse.getForecast() != null) {
            forecastResponse.getForecast().stream().filter(item -> item != null && item.getCasts() != null)
                    .flatMap(item -> item.getCasts().stream())
                    .filter(item -> item != null)
                    .forEach(item -> days.add(new WeatherForecastDay(
                            item.getDate(), item.getDayweather(), item.getDaytemp(), item.getNighttemp(),
                            null, null, null)));
        }
        return new WeatherSnapshot(
                live.getTemperature(), live.getWeather(), live.getHumidity(), live.getWinddirection(), live.getWindpower(),
                live.getReporttime(), null, null, null, false, days.size(), days,
                "GAODE", "GAODE_LIVE", "provider observation");
    }

    /** Uses Open-Meteo's free forecast endpoint with no API key. */
    public WeatherSnapshot getOpenMeteoWeather(double longitude, double latitude) {
        if (!openMeteoEnabled) return null;
        try {
            String url = String.format(Locale.ROOT,
                    "%s?latitude=%.6f&longitude=%.6f&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max"
                            + "&forecast_days=14&timezone=auto%s",
                    openMeteoBaseUrl, latitude, longitude,
                    openMeteoApiKey == null || openMeteoApiKey.isBlank()
                            ? ""
                            : "&apikey=" + URLEncoder.encode(openMeteoApiKey, StandardCharsets.UTF_8.toString()));
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) return null;
            JsonNode root = objectMapper.readTree(response);
            JsonNode current = root.path("current");
            JsonNode daily = root.path("daily");
            JsonNode dates = daily.path("time");
            if (root.path("error").asBoolean(false) || !current.hasNonNull("temperature_2m") || !dates.isArray() || dates.isEmpty()) return null;

            List<WeatherForecastDay> days = new ArrayList<>();
            double rainfall14d = 0D;
            for (int index = 0; index < dates.size(); index++) {
                Double precipitation = numberAt(daily.path("precipitation_sum"), index);
                if (precipitation != null) rainfall14d += precipitation;
                days.add(new WeatherForecastDay(
                        textAt(dates, index),
                        weatherCondition(integerAt(daily.path("weather_code"), index)),
                        decimalText(numberAt(daily.path("temperature_2m_max"), index)),
                        decimalText(numberAt(daily.path("temperature_2m_min"), index)),
                        precipitation,
                        integerAt(daily.path("precipitation_probability_max"), index),
                        integerAt(daily.path("weather_code"), index)));
            }
            Double rainfall24h = days.get(0).precipitationMm();
            Integer probability24h = days.get(0).precipitationProbability();
            String condition = weatherCondition(current.path("weather_code").asInt(-1));
            return new WeatherSnapshot(
                    decimalText(current.path("temperature_2m").doubleValue()),
                    condition,
                    decimalText(current.path("relative_humidity_2m").doubleValue()),
                    windDirection(current.path("wind_direction_10m").doubleValue()),
                    decimalText(current.path("wind_speed_10m").doubleValue()) + " km/h",
                    current.path("time").asText(null), rainfall14d, rainfall24h, probability24h,
                    true, days.size(), days, "OPEN_METEO", "OPEN_METEO_FORECAST", "forecast model update");
        } catch (Exception error) {
            log.warn("Open-Meteo 天气接口不可用: {}", error.getClass().getSimpleName());
            return null;
        }
    }

    private static Double numberAt(JsonNode array, int index) {
        JsonNode value = array.path(index);
        return value.isNumber() ? value.doubleValue() : null;
    }

    private static Integer integerAt(JsonNode array, int index) {
        JsonNode value = array.path(index);
        return value.isNumber() ? value.intValue() : null;
    }

    private static String textAt(JsonNode array, int index) {
        JsonNode value = array.path(index);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String decimalText(Double value) {
        return value == null ? null : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String weatherCondition(int code) {
        if (code == 0) return "晴";
        if (code <= 3) return "多云";
        if (code == 45 || code == 48) return "雾";
        if (code <= 57) return "毛毛雨";
        if (code <= 67 || (code >= 80 && code <= 82)) return "降雨";
        if (code <= 77 || code == 85 || code == 86) return "降雪";
        if (code == 95 || code == 96 || code == 99) return "雷雨";
        return "天气变化";
    }

    private static String windDirection(double degrees) {
        if (!Double.isFinite(degrees)) return "—";
        String[] directions = {"北风", "东北风", "东风", "东南风", "南风", "西南风", "西风", "西北风"};
        int index = (int) Math.floor((degrees + 22.5) / 45.0) % directions.length;
        return directions[index];
    }

    public record WeatherSnapshot(
            String temperature,
            String condition,
            String humidity,
            String windDirection,
            String windPower,
            String observedAt,
            Double rainfall14d,
            Double rainfallForecast24h,
            Integer rainfallProbability24h,
            Boolean rainfallForecast,
            Integer rainfallForecastDays,
            List<WeatherForecastDay> forecast,
            String provider,
            String source,
            String freshness
    ) {}
    
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
