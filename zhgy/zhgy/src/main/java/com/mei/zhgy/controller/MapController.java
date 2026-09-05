package com.mei.zhgy.controller;

import com.mei.zhgy.dto.MapTreeDTO;
import com.mei.zhgy.entity.FarmLocation;
import com.mei.zhgy.result.NoPageResult;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.FarmLocationService;
import com.mei.zhgy.service.MapService;
import com.mei.zhgy.vo.FarmLocationVO;
import com.mei.zhgy.vo.MapFarmVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

@RestController
@Slf4j
@RequestMapping("/map")
public class MapController {
    @Autowired
    private MapService mapService;
    
    @Autowired
    private FarmLocationService farmLocationService;

    @GetMapping("/tree")
    public Result getMapTree(@RequestBody MapTreeDTO mapTree)
    {
        log.info("查询树的列表");
        NoPageResult result = mapService.queryTree(mapTree);
        return Result.success(result);
    }

    @GetMapping("/farm")
    public Result getMapFarm(@RequestBody MapTreeDTO map)
    {
        log.info("查询农场的列表");
        NoPageResult result = mapService.queryFarm(map);
        return Result.success(result);
    }
    
    /**
     * 根据农场ID获取农场位置信息
     * @param farmId
     * @return
     */
    @GetMapping("/farm/{farmId}/locations")
    public Result<FarmLocationVO> getFarmLocations(@PathVariable Integer farmId) {
        log.info("查询农场位置信息，农场ID: {}", farmId);
        
        try {
            List<FarmLocation> locations = farmLocationService.getLocationsByFarmId(farmId);
            
            FarmLocationVO.LocationPoint[] locationPoints = locations.stream()
                    .map(location -> FarmLocationVO.LocationPoint.builder()
                            .id(location.getId())
                            .longitude(location.getLongitude())
                            .latitude(location.getLatitude())
                            .build())
                    .toArray(FarmLocationVO.LocationPoint[]::new);
            
            FarmLocationVO farmLocationVO = FarmLocationVO.builder()
                    .farmId(farmId)
                    .locations(Arrays.asList(locationPoints))
                    .status(0)
                    .build();
            
            return Result.success(farmLocationVO);
        } catch (Exception e) {
            log.error("获取农场位置信息失败", e);
            return Result.error("获取农场位置信息失败");
        }
    }
}