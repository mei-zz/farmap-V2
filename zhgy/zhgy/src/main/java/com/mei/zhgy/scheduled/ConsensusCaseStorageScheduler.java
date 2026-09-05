package com.mei.zhgy.scheduled;

import com.mei.zhgy.entity.UserRequest;
import com.mei.zhgy.mapper.UserRequestMapper;
import com.mei.zhgy.service.CaseStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ConsensusCaseStorageScheduler {
    
    @Autowired
    private UserRequestMapper userRequestMapper;
    
    @Autowired
    private CaseStorageService caseStorageService;
    
    /**
     * 定时处理达成共识的案例（每5分钟执行一次）
     * 将status为3的案例存储到外部知识库，并更新状态为4
     */
    @Scheduled(fixedRate = 300000) // 5分钟执行一次
    public void processConsensusCases() {
        try {
            log.info("开始处理达成共识的案例...");
            
            // 查询所有状态为3（已达成共识）的案例
            List<UserRequest> consensusCases = userRequestMapper.getConsensusCases();
            
            if (consensusCases.isEmpty()) {
                log.info("没有需要处理的共识案例");
                return;
            }
            
            log.info("找到{}个需要处理的共识案例", consensusCases.size());
            
            int successCount = 0;
            for (UserRequest userRequest : consensusCases) {
                try {
                    String requestId = userRequest.getRequestId();
                    log.info("处理案例: {}", requestId);
                    
                    // 将案例存储到外部知识库
                    boolean saved = caseStorageService.saveConsensusCase(requestId);
                    
                    if (saved) {
                        // 更新状态为4（已加入外部数据库）
                        userRequestMapper.updateStatusToStored(requestId);
                        log.info("案例{}已成功存储到外部知识库并更新状态为4", requestId);
                        successCount++;
                    } else {
                        log.warn("案例{}存储到外部知识库失败", requestId);
                    }
                } catch (Exception e) {
                    log.error("处理案例{}时发生错误", userRequest.getRequestId(), e);
                }
            }
            
            log.info("共识案例处理完成，成功处理{}个案例", successCount);
        } catch (Exception e) {
            log.error("处理共识案例时发生错误", e);
        }
    }
}