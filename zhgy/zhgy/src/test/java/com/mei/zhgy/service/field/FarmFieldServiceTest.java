package com.mei.zhgy.service.field;

import com.mei.zhgy.mapper.FarmMapper;
import com.mei.zhgy.mapper.UserMapper;
import com.mei.zhgy.mapper.WeatherMapper;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class FarmFieldServiceTest {
    @Test
    void crossUserFarmAccessFailsClosedBeforeFieldQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FarmMapper farms = mock(FarmMapper.class);
        WeatherMapper weather = mock(WeatherMapper.class);
        UserMapper users = mock(UserMapper.class);
        when(farms.getFarmById(1)).thenReturn(UserLoginResponseVO.FarmVO.builder().id(1).name("Farm").userId(1).build());
        FarmFieldService service = new FarmFieldService(jdbc, farms, weather, users);

        FieldAccessException error = assertThrows(FieldAccessException.class, () -> service.list(1, 2L));

        assertEquals(403, error.getHttpStatus());
        assertEquals("FARM_ACCESS_DENIED", error.getCode());
        verifyNoInteractions(jdbc);
    }
}
