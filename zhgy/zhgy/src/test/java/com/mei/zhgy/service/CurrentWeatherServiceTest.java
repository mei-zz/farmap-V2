package com.mei.zhgy.service;

import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrentWeatherServiceTest {
    @Mock FarmFieldService fields;
    @Mock WeatherApiService weatherApi;

    @Test
    void returnsProviderObservationForAuthorizedFarm() {
        CurrentWeatherService service = new CurrentWeatherService(fields, weatherApi);
        UserLoginResponseVO.FarmVO farm = UserLoginResponseVO.FarmVO.builder()
                .id(1).name("云阳").center("108.1302,31.1706").build();
        when(fields.requireFarm(1, 1L)).thenReturn(farm);
        when(weatherApi.isConfigured()).thenReturn(true);
        when(weatherApi.getWeather(108.1302, 31.1706)).thenReturn(new WeatherApiService.WeatherSnapshot(
                "25", "晴", "60", "东风", "≤3", "2026-09-08 18:00:00",
                null, null, null, false, 0, List.of(), "GAODE", "GAODE_LIVE", "provider observation"));

        var result = service.get(1, 1L);

        assertEquals("READY", result.status());
        assertEquals("25", result.temperature());
        assertEquals("GAODE", result.provider());
        verify(weatherApi).getWeather(108.1302, 31.1706);
    }

    @Test
    void configuredMissingDoesNotCallProvider() {
        CurrentWeatherService service = new CurrentWeatherService(fields, weatherApi);
        UserLoginResponseVO.FarmVO farm = UserLoginResponseVO.FarmVO.builder().id(8).name("大足").build();
        when(fields.requireFarm(8, 1L)).thenReturn(farm);
        when(weatherApi.isConfigured()).thenReturn(false);

        var result = service.get(8, 1L);

        assertEquals("NOT_CONFIGURED", result.status());
        verify(weatherApi, never()).getWeather(anyDouble(), anyDouble());
    }
}
