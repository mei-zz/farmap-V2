package com.mei.zhgy.controller;

import com.mei.zhgy.entity.Guidance;
import com.mei.zhgy.mapper.FarmMapper;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.GuidanceService;
import com.mei.zhgy.dto.UpdateGuidanceDTO;
import com.mei.zhgy.vo.GuidanceVO;
import com.mei.zhgy.vo.GuidanceResponseVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@Slf4j
@RequestMapping("/guidance")
@Api(tags = "农事指导相关接口")
public class GuidanceController {
    
    @Autowired
    private GuidanceService guidanceService;
    
    @Autowired
    private FarmMapper farmMapper;
    
    /**
     * 根据农场ID和月份获取所有类型的指导信息
     * @param farmId
     * @param month
     * @return
     */
    @GetMapping("/{farmId}/{month}")
    @ApiOperation(value = "根据农场ID和月份获取农事指导")
    public Result<Map<String, GuidanceVO>> getGuidanceByFarmAndMonth(
            @PathVariable Integer farmId,
            @PathVariable Integer month) {
        
        log.info("获取农事指导信息：农场ID={}, 月份={}", farmId, month);
        
        try {
            // 参数校验
            if (month < 0 || month > 11) {
                return Result.error("月份参数错误，应在0-11之间");
            }
            
            // 获取指导信息
            List<Guidance> guidances = guidanceService.getByFarmIdAndMonth(farmId, month);
            
            // 按指导类型分组
            Map<String, List<Guidance>> groupedGuidances = guidances.stream()
                    .collect(Collectors.groupingBy(Guidance::getGuidanceType));
            
            // 转换为VO对象
            Map<String, GuidanceVO> result = groupedGuidances.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> {
                                List<GuidanceVO.GuidanceItem> items = entry.getValue().stream()
                                        .map(g -> GuidanceVO.GuidanceItem.builder()
                                                .isFormula(g.getIsFormula())
                                                .text(g.getText())
                                                .build())
                                        .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
                                
                                return GuidanceVO.builder()
                                        .guidanceType(entry.getKey())
                                        .items(items)
                                        .status(0)
                                        .build();
                            }
                    ));
            
            return Result.success(result);
        } catch (Exception e) {
            log.error("获取农事指导信息失败", e);
            return Result.error("获取农事指导信息失败");
        }
    }
    
    /**
     * 根据农场ID、月份和指导类型获取指导信息
     * @param farmId
     * @param month
     * @param guidanceType
     * @return
     */
    @GetMapping("/{farmId}/{month}/{guidanceType}")
    @ApiOperation(value = "根据农场ID、月份和指导类型获取农事指导")
    public Result<GuidanceVO> getGuidanceByFarmAndMonthAndType(
            @PathVariable Integer farmId,
            @PathVariable Integer month,
            @PathVariable String guidanceType) {
        
        log.info("获取农事指导信息：农场ID={}, 月份={}, 指导类型={}", farmId, month, guidanceType);
        
        try {
            // 参数校验
            if (month < 0 || month > 11) {
                return Result.error("月份参数错误，应在0-11之间");
            }
            
            // 获取指导信息
            List<Guidance> guidances = guidanceService.getByFarmIdAndMonthAndType(farmId, month, guidanceType);
            
            // 转换为VO对象
            List<GuidanceVO.GuidanceItem> items = guidances.stream()
                    .map(g -> GuidanceVO.GuidanceItem.builder()
                            .isFormula(g.getIsFormula())
                            .text(g.getText())
                            .build())
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            
            GuidanceVO guidanceVO = GuidanceVO.builder()
                    .guidanceType(guidanceType)
                    .items(items)
                    .status(0)
                    .build();
            
            return Result.success(guidanceVO);
        } catch (Exception e) {
            log.error("获取农事指导信息失败", e);
            return Result.error("获取农事指导信息失败");
        }
    }
    
    /**
     * 获取指导信息新接口（支持根据农场类型获取默认指导）
     * @param farmId 农场ID
     * @param farmType 农场类型
     * @param month 月份
     * @return
     */
    @GetMapping("/get")
    @ApiOperation(value = "获取指导信息（支持根据农场类型获取默认指导）")
    public Result<GuidanceResponseVO> getGuidance(
            @RequestParam(required = false) Integer farmId,
            @RequestParam(required = false) String farmType,
            @RequestParam Integer month) {
        
        log.info("获取指导信息：农场ID={}, 农场类型={}, 月份={}", farmId, farmType, month);
        
        try {
            // 参数校验
            if (month < 0 || month > 11) {
                return Result.error("月份参数错误，应在0-11之间");
            }
            
            // 如果没有提供farmId和farmType，则返回错误
            if (farmId == null && farmType == null) {
                return Result.error("请提供farmId或farmType参数");
            }
            
            // 如果提供了farmId，则优先使用farmId获取指导信息
            // 如果没有获取到，则使用farmType获取默认指导信息
            String typeToUse = farmType;
            if (farmId != null) {
                // 获取农场类型
                typeToUse = farmMapper.getFarmTypeById(farmId);
            }
            
            if (typeToUse == null || typeToUse.isEmpty()) {
                return Result.error("无法确定农场类型");
            }
            
            // 获取各类指导信息
            List<Guidance> bodyGuidances = guidanceService.getByFarmTypeAndMonthAndType(typeToUse, month, "body");
            List<Guidance> fertileGuidances = guidanceService.getByFarmTypeAndMonthAndType(typeToUse, month, "fertite");
            List<Guidance> pestGuidances = guidanceService.getByFarmTypeAndMonthAndType(typeToUse, month, "pest");
            List<Guidance> parkGuidances = guidanceService.getByFarmTypeAndMonthAndType(typeToUse, month, "park");
            
            // 转换为响应VO
            List<GuidanceResponseVO.GuidanceItem> bodyItems = bodyGuidances.stream()
                    .map(g -> GuidanceResponseVO.GuidanceItem.builder()
                            .id(g.getId())
                            .text(g.getText())
                            .isFormula(g.getIsFormula())
                            .build())
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            
            List<GuidanceResponseVO.GuidanceItem> fertileItems = fertileGuidances.stream()
                    .map(g -> GuidanceResponseVO.GuidanceItem.builder()
                            .id(g.getId())
                            .text(g.getText())
                            .isFormula(g.getIsFormula())
                            .build())
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            
            List<GuidanceResponseVO.GuidanceItem> pestItems = pestGuidances.stream()
                    .map(g -> GuidanceResponseVO.GuidanceItem.builder()
                            .id(g.getId())
                            .text(g.getText())
                            .isFormula(g.getIsFormula())
                            .build())
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            
            List<GuidanceResponseVO.GuidanceItem> parkItems = parkGuidances.stream()
                    .map(g -> GuidanceResponseVO.GuidanceItem.builder()
                            .id(g.getId())
                            .text(g.getText())
                            .isFormula(g.getIsFormula())
                            .build())
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            
            GuidanceResponseVO responseVO = GuidanceResponseVO.builder()
                    .body(bodyItems)
                    .fertile(fertileItems)
                    .pest(pestItems)
                    .park(parkItems)
                    .build();
            
            return Result.success(responseVO);
        } catch (Exception e) {
            log.error("获取指导信息失败", e);
            return Result.error("获取指导信息失败: " + e.getMessage());
        }
    }
    
    /**
     * 更新农场指导信息
     * @param updateGuidanceDTO
     * @return
     */
    @PutMapping("/update")
    @ApiOperation(value = "更新农场指导信息")
    public Result<String> updateGuidance(@RequestBody UpdateGuidanceDTO updateGuidanceDTO) {
        log.info("更新农场指导信息：farmId={}, guidanceType={}", updateGuidanceDTO.getFarmId(), updateGuidanceDTO.getGuidanceType());
        
        try {
            // 参数校验
            if (updateGuidanceDTO.getFarmId() == null) {
                return Result.error("farmId不能为空");
            }
            
            if (updateGuidanceDTO.getGuidanceType() == null || updateGuidanceDTO.getGuidanceType().isEmpty()) {
                return Result.error("guidanceType不能为空");
            }
            
            if (updateGuidanceDTO.getContent() == null) {
                return Result.error("content不能为空");
            }
            
            // 验证guidanceType是否有效
            String guidanceType = updateGuidanceDTO.getGuidanceType();
            if (!"body".equals(guidanceType) && !"fertite".equals(guidanceType) && 
                !"pest".equals(guidanceType) && !"park".equals(guidanceType)) {
                return Result.error("guidanceType必须是以下值之一: body, fertite, pest, park");
            }
            
            // 转换DTO中的内容为Guidance实体列表
            List<Guidance> guidances = new ArrayList<>();
            for (UpdateGuidanceDTO.GuidanceContent content : updateGuidanceDTO.getContent()) {
                Guidance guidance = new Guidance();
                guidance.setText(content.getText());
                guidance.setIsFormula(content.getIsFormula());
                guidances.add(guidance);
            }
            
            // 调用服务更新指导信息（这里使用默认月份0，实际应用中可能需要从其他地方获取）
            guidanceService.updateGuidanceByFarmIdAndType(
                    updateGuidanceDTO.getFarmId(), 
                    updateGuidanceDTO.getGuidanceType(), 
                    0, // 默认月份为0，实际应用中可能需要从请求参数中获取
                    guidances);
            
            return Result.success("指导信息更新成功");
        } catch (Exception e) {
            log.error("更新农场指导信息失败", e);
            return Result.error("更新农场指导信息失败: " + e.getMessage());
        }
    }
}