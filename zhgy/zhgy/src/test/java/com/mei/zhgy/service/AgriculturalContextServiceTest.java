package com.mei.zhgy.service;

import com.mei.zhgy.mapper.GuidanceMapper;
import com.mei.zhgy.mapper.WeatherMapper;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AgriculturalContextServiceTest {
    @Test
    void crossUserFarmAccessFailsBeforeAgriculturalQueries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FarmFieldService fields = mock(FarmFieldService.class);
        WeatherMapper weather = mock(WeatherMapper.class);
        GuidanceMapper guidance = mock(GuidanceMapper.class);
        CurrentWeatherService currentWeather = mock(CurrentWeatherService.class);
        when(fields.requireFarm(1, 2L)).thenThrow(new FieldAccessException(403, "FARM_ACCESS_DENIED", "farm denied"));
        AgriculturalContextService service = new AgriculturalContextService(jdbc, fields, weather, guidance, currentWeather);

        FieldAccessException error = assertThrows(FieldAccessException.class,
                () -> service.get(1, 2L, null, null, null, null));

        assertEquals(403, error.getHttpStatus());
        assertEquals("FARM_ACCESS_DENIED", error.getCode());
        verifyNoInteractions(jdbc, weather, guidance, currentWeather);
    }

    @Test
    void invalidMonthFailsBeforeReadingFarmContextSources() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FarmFieldService fields = mock(FarmFieldService.class);
        WeatherMapper weather = mock(WeatherMapper.class);
        GuidanceMapper guidance = mock(GuidanceMapper.class);
        CurrentWeatherService currentWeather = mock(CurrentWeatherService.class);
        when(fields.requireFarm(1, 1L)).thenReturn(UserLoginResponseVO.FarmVO.builder().id(1).type("citrus").build());
        AgriculturalContextService service = new AgriculturalContextService(jdbc, fields, weather, guidance, currentWeather);

        FieldAccessException error = assertThrows(FieldAccessException.class,
                () -> service.get(1, 1L, 12, null, null, null));

        assertEquals(400, error.getHttpStatus());
        assertEquals("MONTH_INVALID", error.getCode());
        verifyNoInteractions(jdbc, weather, guidance, currentWeather);
    }
}
