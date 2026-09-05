package com.mei.zhgy.service;

import com.github.pagehelper.PageInfo;
import com.mei.zhgy.dto.ExpertRevisionDTO;
import com.mei.zhgy.vo.CaseDetailVO;
import com.mei.zhgy.vo.PendingRevisionCaseVO;
import com.mei.zhgy.vo.RevisionHistoryVO;
import com.mei.zhgy.vo.RequestDataComparisonVO;

import java.util.List;

public interface ExpertService {
    
    /**
     * 获取待专家修改的案例列表
     * @param expertId 专家ID
     * @param page 页码
     * @param size 每页条数
     * @return 案例列表
     */
    PageInfo<PendingRevisionCaseVO> getPendingRevisionCases(Long expertId, int page, int size);
    
    /**
     * 获取案例详情
     * @param requestId 案例ID
     * @param expertId 专家ID
     * @return 案例详情
     */
    CaseDetailVO getCaseDetail(String requestId, Long expertId);
    
    /**
     * 提交专家修改
     * @param requestId 案例ID
     * @param expertId 专家ID
     * @param revisionDTO 修改信息
     * @return 是否提交成功
     */
    boolean submitRevision(String requestId, Long expertId, ExpertRevisionDTO revisionDTO);
    
    /**
     * 获取案例修改历史记录
     * @param requestId 案例ID
     * @return 案例修改历史记录列表
     */
    List<RevisionHistoryVO> getRevisionHistory(String requestId);
    
    /**
     * 判断案例是否达到共识阈值
     * @param requestId 案例ID
     * @return 是否达到共识阈值
     */
    boolean checkConsensus(String requestId);
    
    /**
     * 获取所有被修改过的request_id
     * @return 所有被修改过的request_id列表
     */
    List<String> getAllModifiedRequestIds();
    
    /**
     * 根据requestId获取数据修改前后对比
     * @param requestId 请求ID
     * @return 数据修改前后对比
     */
    RequestDataComparisonVO getRequestDataComparison(String requestId);
}