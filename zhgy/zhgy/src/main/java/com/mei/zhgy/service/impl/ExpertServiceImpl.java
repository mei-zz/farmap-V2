package com.mei.zhgy.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.mei.zhgy.dto.ExpertRevisionDTO;
import com.mei.zhgy.entity.ExpertRevision;
import com.mei.zhgy.entity.InitialResult;
import com.mei.zhgy.mapper.ExpertMapper;
import com.mei.zhgy.mapper.UserRequestMapper;
import com.mei.zhgy.service.ExpertService;
import com.mei.zhgy.vo.CaseDetailVO;
import com.mei.zhgy.vo.PendingRevisionCaseVO;
import com.mei.zhgy.vo.RevisionHistoryVO;
import com.mei.zhgy.vo.RequestDataComparisonVO;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
public class ExpertServiceImpl implements ExpertService {
    
    @Autowired
    private ExpertMapper expertMapper;
    
    @Autowired
    private UserRequestMapper userRequestMapper;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    
    @Override
    public PageInfo<PendingRevisionCaseVO> getPendingRevisionCases(Long expertId, int page, int size) {
        log.info("获取待专家修改的案例列表，专家ID: {}, 页码: {}, 每页条数: {}", expertId, page, size);
        
        // 开启分页
        PageHelper.startPage(page, size);
        
        // 查询待修改的案例列表
        List<PendingRevisionCaseVO> cases = expertMapper.getPendingRevisionCases(expertId);
        
        PageInfo<PendingRevisionCaseVO> pageInfo = new PageInfo<>(cases);
        log.info("查询到{}条待修改的案例", pageInfo.getTotal());
        
        return pageInfo;
    }
    
    @Override
    public CaseDetailVO getCaseDetail(String requestId, Long expertId) {
        log.info("获取案例详情，案例ID: {}, 专家ID: {}", requestId, expertId);
        
        // 获取用户请求信息
        CaseDetailVO.UserRequestInfo userRequestInfo = expertMapper.getUserRequestInfo(requestId);
        if (userRequestInfo == null) {
            log.warn("未找到用户请求信息，案例ID: {}", requestId);
            throw new IllegalArgumentException("未找到案例信息");
        }
        
        // 检查案例状态是否为可修改状态（0、1或2）
        // 状态0: 待处理，状态1: 已生成初始结果，状态2: 专家修正中
        if (userRequestInfo.getStatus() != 0 && userRequestInfo.getStatus() != 1 && userRequestInfo.getStatus() != 2) {
            log.warn("案例状态不正确，案例ID: {}, 状态: {}", requestId, userRequestInfo.getStatus());
            throw new IllegalArgumentException("案例状态不正确，无法查看");
        }
        
        // 获取初始结果信息
        InitialResult initialResult = expertMapper.getInitialResultInfo(requestId);
        CaseDetailVO.InitialResultInfo initialResultInfo = null;
        if (initialResult != null) {
            initialResultInfo = CaseDetailVO.InitialResultInfo.builder()
                    .jsonData(initialResult.getJsonData())
                    .modelVersion(initialResult.getModelVersion())
                    .build();
        }
        
        if (initialResultInfo == null) {
            log.warn("未找到初始结果信息，案例ID: {}", requestId);
            throw new IllegalArgumentException("未找到案例的初始分析结果");
        }
        
        // 获取专家修改记录列表
        List<CaseDetailVO.RevisionRecordVO> revisionRecords = expertMapper.getRevisionRecords(requestId);
        
        // 构建返回结果
        CaseDetailVO caseDetailVO = new CaseDetailVO();
        caseDetailVO.setUserRequestInfo(userRequestInfo);
        caseDetailVO.setInitialResultInfo(initialResultInfo);
        caseDetailVO.setRevisionRecords(revisionRecords);
        
        log.info("成功获取案例详情，案例ID: {}", requestId);
        return caseDetailVO;
    }
    
    @Override
    @Transactional
    public boolean submitRevision(String requestId, Long expertId, ExpertRevisionDTO revisionDTO) {
        log.info("专家提交修改，案例ID: {}, 专家ID: {}", requestId, expertId);
        
        // 检查案例是否存在
        CaseDetailVO.UserRequestInfo userRequestInfo = expertMapper.getUserRequestInfo(requestId);
        if (userRequestInfo == null) {
            log.warn("未找到用户请求信息，案例ID: {}", requestId);
            throw new IllegalArgumentException("未找到案例信息");
        }
        
        // 检查案例状态是否为可修改状态（0、1或2）
        if (userRequestInfo.getStatus() != 0 && userRequestInfo.getStatus() != 1 && userRequestInfo.getStatus() != 2) {
            log.warn("案例状态不正确，案例ID: {}, 状态: {}", requestId, userRequestInfo.getStatus());
            throw new IllegalArgumentException("案例状态不正确，无法修改");
        }
        
        // 生成修改记录ID
        String revisionId = UUID.randomUUID().toString();
        
        // 插入专家修改记录
        try {
            expertMapper.insertExpertRevision(
                    revisionId,
                    requestId,
                    expertId,
                    revisionDTO.getRevisedJson(),
                    revisionDTO.getIsAgree(),
                    revisionDTO.getRevisionNotes()
            );
            log.info("插入专家修改记录成功，修改记录ID: {}", revisionId);
        } catch (Exception e) {
            log.error("插入专家修改记录失败，修改记录ID: {}", revisionId, e);
            throw new RuntimeException("提交修改失败: 数据库插入错误");
        }
        
        // 更新用户请求状态
        if (userRequestInfo.getStatus() == 0 || userRequestInfo.getStatus() == 1) {
            // 原状态为0（待处理）或1（已生成初始结果），更新为状态2（专家修正中）
            int updated = expertMapper.updateUserRequestStatus(requestId, userRequestInfo.getStatus(), 2);
            if (updated > 0) {
                log.info("更新用户请求状态成功，案例ID: {} 从状态{}更新为状态2", requestId, userRequestInfo.getStatus());
            } else {
                log.warn("更新用户请求状态失败，案例ID: {} 状态可能已改变", requestId);
            }
        } else {
            log.info("用户请求状态保持不变，案例ID: {} 当前状态: {}", requestId, userRequestInfo.getStatus());
        }
        
        // 提交修改后自动触发共识判断
        try {
            boolean consensusReached = checkConsensus(requestId);
            if (consensusReached) {
                log.info("案例已达成共识，案例ID: {}", requestId);
            } else {
                log.info("案例未达成共识，案例ID: {}", requestId);
            }
        } catch (Exception e) {
            log.error("共识判断过程出错，案例ID: {}", requestId, e);
        }
        
        return true;
    }
    
    @Override
    public List<RevisionHistoryVO> getRevisionHistory(String requestId) {
        log.info("获取案例修改历史记录，案例ID: {}", requestId);
        
        // 检查案例是否存在
        CaseDetailVO.UserRequestInfo userRequestInfo = expertMapper.getUserRequestInfo(requestId);
        if (userRequestInfo == null) {
            log.warn("未找到用户请求信息，案例ID: {}", requestId);
            throw new IllegalArgumentException("未找到案例信息");
        }
        
        // 获取案例修改历史记录
        List<RevisionHistoryVO> revisionHistory = expertMapper.getRevisionHistoryByRequestId(requestId);
        
        log.info("成功获取案例修改历史记录，案例ID: {}，共{}条记录", requestId, revisionHistory.size());
        return revisionHistory;
    }
    
    @Override
    public boolean checkConsensus(String requestId) {
        log.info("开始共识判断，案例ID: {}", requestId);
        
        // 统计参与修改的专家数量
        int expertCount = expertMapper.countExpertRevisions(requestId);
        log.info("参与修改的专家数量: {}", expertCount);
        
        // 判断专家数量是否达到阈值（至少3位专家）
        if (expertCount < 3) {
            log.info("专家数量未达到阈值，当前: {}，阈值: 3", expertCount);
            return false;
        }
        
        // 获取所有修改JSON数据
        List<String> revisedJsonList = expertMapper.getRevisedJsonList(requestId);
        log.info("获取到{}条修改记录", revisedJsonList.size());
        
        // 实现核心字段共识度计算逻辑
        try {
            boolean consensusReached = calculateConsensus(revisedJsonList);
            
            // 如果专家数量>=3且共识度达标，更新案例状态为3（已达成共识）
            if (consensusReached) {
                // 获取当前状态
                Integer currentStatus = expertMapper.getUserRequestStatus(requestId);
                if (currentStatus != null && currentStatus != 3) {
                    // 更新状态为3（已达成共识）
                    expertMapper.updateUserRequestStatus(requestId, currentStatus, 3);
                    log.info("更新案例状态为已达成共识，案例ID: {}", requestId);
                } else {
                    log.info("案例状态无需更新，案例ID: {}，当前状态: {}", requestId, currentStatus);
                }
                return true;
            } else {
                log.info("未达成共识，案例ID: {}", requestId);
                return false;
            }
        } catch (Exception e) {
            log.error("计算共识度时发生错误，案例ID: {}", requestId, e);
            return false;
        }
    }
    
    /**
     * 计算专家修改的共识度
     * @param revisedJsonList 专家修改的JSON数据列表
     * @return 是否达成共识
     */
    private boolean calculateConsensus(List<String> revisedJsonList) {
        if (revisedJsonList == null || revisedJsonList.isEmpty()) {
            return false;
        }
        
        try {
            // 定义核心字段
            List<String> coreFields = Arrays.asList(
                "树种识别.种类",
                "病虫害诊断.疑似病害.病害名称",
                "病虫害诊断.疑似虫害.虫害名称",
                "营养状况.主要缺乏元素"
            );
            
            // 解析所有JSON数据
            List<Map<String, Object>> parsedJsonList = new ArrayList<>();
            for (String json : revisedJsonList) {
                Map<String, Object> map = objectMapper.readValue(json, Map.class);
                parsedJsonList.add(map);
            }
            
            // 对每个核心字段计算共识度
            int totalFields = coreFields.size();
            int consensusFields = 0;
            
            for (String field : coreFields) {
                if (isFieldConsensusReached(parsedJsonList, field)) {
                    consensusFields++;
                }
            }
            
            // 计算共识率（要求至少70%的核心字段达成共识，便于测试）
            double consensusRate = (double) consensusFields / totalFields;
            log.info("核心字段共识率: {}/{} = {}", consensusFields, totalFields, consensusRate);
            
            // 如果共识率超过70%，则认为达成共识
            return consensusRate >= 0.7;
        } catch (Exception e) {
            log.error("解析JSON或计算共识度时发生错误", e);
            return false;
        }
    }
    
    /**
     * 判断指定字段是否在专家修改中达成共识
     * @param parsedJsonList 解析后的JSON数据列表
     * @param fieldPath 字段路径（如"树种识别.种类"）
     * @return 是否达成共识
     */
    private boolean isFieldConsensusReached(List<Map<String, Object>> parsedJsonList, String fieldPath) {
        // 提取字段值
        List<String> values = new ArrayList<>();
        for (Map<String, Object> jsonMap : parsedJsonList) {
            Object value = getValueByPath(jsonMap, fieldPath);
            if (value != null) {
                values.add(value.toString());
            } else {
                // 如果直接路径找不到，尝试查找兼容路径
                Object compatibleValue = getCompatibleValue(jsonMap, fieldPath);
                if (compatibleValue != null) {
                    values.add(compatibleValue.toString());
                }
            }
        }
        
        if (values.isEmpty()) {
            return false;
        }
        
        // 统计每个值出现的次数
        Map<String, Integer> valueCount = new HashMap<>();
        for (String value : values) {
            valueCount.put(value, valueCount.getOrDefault(value, 0) + 1);
        }
        
        // 找到出现次数最多的值
        int maxCount = 0;
        for (int count : valueCount.values()) {
            if (count > maxCount) {
                maxCount = count;
            }
        }
        
        // 计算该值在所有值中的占比（要求至少2/3的专家意见一致）
        double agreementRate = (double) maxCount / values.size();
        log.debug("字段'{}'的共识率: {}/{} = {}", fieldPath, maxCount, values.size(), agreementRate);
        
        return agreementRate >= 0.67; // 至少2/3专家意见一致
    }
    
    /**
     * 根据路径获取嵌套Map中的值
     * @param map 嵌套的Map结构
     * @param path 字段路径，以"."分隔
     * @return 字段值
     */
    private Object getValueByPath(Map<String, Object> map, String path) {
        String[] keys = path.split("\\.");
        Object current = map;
        
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(key);
            } else {
                return null;
            }
        }
        
        return current;
    }
    
    /**
     * 获取兼容路径的值（处理原始数据结构）
     * @param map 嵌套的Map结构
     * @param fieldPath 字段路径
     * @return 兼容的字段值
     */
    private Object getCompatibleValue(Map<String, Object> map, String fieldPath) {
        // 处理特殊情况，兼容原始数据结构
        if ("病虫害诊断.疑似病害.病害名称".equals(fieldPath)) {
            Object diagnosis = getValueByPath(map, "病虫害诊断");
            if (diagnosis instanceof Map) {
                Object suspectedDisease = ((Map<?, ?>) diagnosis).get("疑似病害");
                if (suspectedDisease != null) {
                    // 如果疑似病害是字符串，直接返回
                    if (suspectedDisease instanceof String) {
                        return suspectedDisease;
                    }
                    // 如果疑似病害是Map，尝试获取病害名称
                    else if (suspectedDisease instanceof Map) {
                        return ((Map<?, ?>) suspectedDisease).get("病害名称");
                    }
                }
            } else if (diagnosis instanceof String) {
                return diagnosis;
            }
        } else if ("病虫害诊断.疑似虫害.虫害名称".equals(fieldPath)) {
            Object diagnosis = getValueByPath(map, "病虫害诊断");
            if (diagnosis instanceof Map) {
                Object suspectedPest = ((Map<?, ?>) diagnosis).get("虫害迹象");
                if (suspectedPest != null) {
                    // 如果虫害迹象是字符串，直接返回
                    if (suspectedPest instanceof String) {
                        // 如果是"无"或其他表示没有虫害的词，返回"无"
                        String pestStr = suspectedPest.toString().trim();
                        if ("无".equals(pestStr) || "未见".equals(pestStr) || "没有".equals(pestStr)) {
                            return "无";
                        }
                        return suspectedPest;
                    }
                    // 如果虫害迹象是Map，尝试获取虫害名称
                    else if (suspectedPest instanceof Map) {
                        return ((Map<?, ?>) suspectedPest).get("虫害名称");
                    }
                }
                
                // 如果没有虫害迹象字段，检查疑似虫害字段
                Object suspectedPestObj = ((Map<?, ?>) diagnosis).get("疑似虫害");
                if (suspectedPestObj != null) {
                    if (suspectedPestObj instanceof String) {
                        String pestStr = suspectedPestObj.toString().trim();
                        if ("无".equals(pestStr) || "未见".equals(pestStr) || "没有".equals(pestStr)) {
                            return "无";
                        }
                        return suspectedPestObj;
                    }
                }
            } else if (diagnosis instanceof String) {
                String diagnosisStr = diagnosis.toString().trim();
                if ("无".equals(diagnosisStr)) {
                    return "无";
                }
                return diagnosis;
            }
        } else if ("营养状况.主要缺乏元素".equals(fieldPath)) {
            Object nutrition = getValueByPath(map, "营养状况");
            if (nutrition instanceof Map) {
                Object mainDeficiency = ((Map<?, ?>) nutrition).get("主要缺乏元素");
                if (mainDeficiency != null) {
                    return mainDeficiency;
                }
                // 如果没有主要缺乏元素字段，检查是否有"未见明显缺乏症状"等描述
                Object otherElements = ((Map<?, ?>) nutrition).get("中微量元素");
                if (otherElements != null && otherElements.toString().contains("未见明显缺乏")) {
                    return "无";
                }
                
                // 检查各个营养元素状态
                Object nState = ((Map<?, ?>) nutrition).get("氮素状态");
                Object pState = ((Map<?, ?>) nutrition).get("磷素状态");
                Object kState = ((Map<?, ?>) nutrition).get("钾素状态");
                
                // 如果所有元素状态都表示正常，则认为没有缺乏元素
                boolean allNormal = true;
                if (nState != null && nState.toString().contains("缺乏")) allNormal = false;
                if (pState != null && pState.toString().contains("缺乏")) allNormal = false;
                if (kState != null && kState.toString().contains("缺乏")) allNormal = false;
                
                if (allNormal) {
                    return "无";
                }
            }
        } else if ("树种识别.种类".equals(fieldPath)) {
            Object treeRecognition = getValueByPath(map, "树种识别");
            if (treeRecognition instanceof Map) {
                Object treeType = ((Map<?, ?>) treeRecognition).get("种类");
                if (treeType != null) {
                    return treeType;
                }
            }
        }
        
        return null;
    }
    
    @Override
    public List<String> getAllModifiedRequestIds() {
        log.info("获取所有被修改过的request_id");
        
        // 从数据库获取所有被修改过的request_id
        List<String> modifiedRequestIds = expertMapper.getAllModifiedRequestIds();
        
        log.info("成功获取被修改过的request_id，共{}个", modifiedRequestIds.size());
        return modifiedRequestIds;
    }
    
    @Override
    public RequestDataComparisonVO getRequestDataComparison(String requestId) {
        log.info("获取request_id数据修改前后对比，requestId: {}", requestId);
        
        // 检查案例是否存在
        CaseDetailVO.UserRequestInfo userRequestInfo = expertMapper.getUserRequestInfo(requestId);
        if (userRequestInfo == null) {
            log.warn("未找到用户请求信息，requestId: {}", requestId);
            throw new IllegalArgumentException("未找到案例信息");
        }
        
        // 获取初始结果信息（修改前的数据）
        InitialResult initialResult = expertMapper.getInitialResultByRequestId(requestId);
        if (initialResult == null) {
            log.warn("未找到初始结果信息，requestId: {}", requestId);
            throw new IllegalArgumentException("未找到初始结果信息");
        }
        
        // 获取专家修改记录列表（修改后的数据）
        List<ExpertRevision> expertRevisions = expertMapper.getExpertRevisionsByRequestId(requestId);
        
        // 构建修改数据列表
        List<RequestDataComparisonVO.ExpertRevisionData> revisionDataList = expertRevisions.stream()
                .map(revision -> RequestDataComparisonVO.ExpertRevisionData.builder()
                        .revisionId(revision.getRevisionId())
                        .expertId(revision.getExpertId())
                        .revisedJson(revision.getRevisedJson())
                        .revisionTime(revision.getRevisionTime())
                        .isAgree(revision.getIsAgree())
                        .revisionNotes(revision.getRevisionNotes())
                        .build())
                .collect(java.util.stream.Collectors.toList());
        
        // 构建返回结果
        RequestDataComparisonVO comparisonVO = RequestDataComparisonVO.builder()
                .requestId(requestId)
                .initialJson(initialResult.getJsonData())
                .revisionDataList(revisionDataList)
                .initialGenerateTime(initialResult.getGenerateTime())
                .revisionCount(revisionDataList.size())
                .build();
        
        log.info("成功获取request_id数据修改前后对比，requestId: {}，修改次数: {}", requestId, revisionDataList.size());
        return comparisonVO;
    }
}