package com.mei.zhgy.controller;

import com.github.pagehelper.PageInfo;
import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.dto.ExpertRevisionDTO;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.ExpertService;
import com.mei.zhgy.vo.CaseDetailVO;
import com.mei.zhgy.vo.PendingRevisionCaseVO;
import com.mei.zhgy.vo.RequestDataComparisonVO;
import com.mei.zhgy.vo.RevisionHistoryVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/expert")
@Slf4j
@Api(tags = "专家接口")
public class ExpertController {
    
    @Autowired
    private ExpertService expertService;
    
    /**
     * 获取待专家修改的案例列表
     * @param page 页码，默认为1
     * @param size 每页条数，默认为10
     * @return 案例列表
     */
    @GetMapping("/pending-cases")
    @ApiOperation("获取待专家修改的案例列表")
    public Result<PageInfo<PendingRevisionCaseVO>> getPendingRevisionCases(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        try {
            log.info("收到获取待专家修改案例列表请求，页码: {}，每页条数: {}", page, size);
            
            // 从token中获取用户ID
            Long userId = BaseContext.getCurrentId();
            if (userId == null) {
                log.warn("用户未登录");
                return Result.error("用户未登录");
            }
            
            log.info("当前用户ID: {}", userId);
            
            // 获取待修改的案例列表
            PageInfo<PendingRevisionCaseVO> cases = expertService.getPendingRevisionCases(userId, page, size);
            
            log.info("成功获取待修改案例列表，共{}条记录", cases.getTotal());
            return Result.success(cases);
        } catch (Exception e) {
            log.error("获取待专家修改案例列表失败", e);
            return Result.error("获取待专家修改案例列表失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取案例详情
     * @param requestId 案例ID
     * @return 案例详情
     */
    @GetMapping("/cases/{requestId}")
    @ApiOperation("获取案例详情")
    public Result<CaseDetailVO> getCaseDetail(@PathVariable("requestId") String requestId) {
        try {
            log.info("收到获取案例详情请求，案例ID: {}", requestId);
            
            // 从token中获取用户ID
            Long userId = BaseContext.getCurrentId();
            if (userId == null) {
                log.warn("用户未登录");
                return Result.error("用户未登录");
            }
            
            log.info("当前用户ID: {}", userId);
            
            // 获取案例详情
            CaseDetailVO caseDetail = expertService.getCaseDetail(requestId, userId);
            
            log.info("成功获取案例详情，案例ID: {}", requestId);
            return Result.success(caseDetail);
        } catch (Exception e) {
            log.error("获取案例详情失败，案例ID: {}", requestId, e);
            return Result.error("获取案例详情失败: " + e.getMessage());
        }
    }
    
    /**
     * 提交专家修改
     * @param requestId 案例ID
     * @param revisionDTO 修改信息
     * @return 是否提交成功
     */
    @PostMapping("/cases/{requestId}/revise")
    @ApiOperation("提交专家修改")
    public Result<String> submitRevision(@PathVariable("requestId") String requestId,
                                         @RequestBody ExpertRevisionDTO revisionDTO) {
        try {
            log.info("收到专家提交修改请求，案例ID: {}", requestId);
            
            // 从token中获取用户ID
            Long userId = BaseContext.getCurrentId();
            if (userId == null) {
                log.warn("用户未登录");
                return Result.error("用户未登录");
            }
            
            log.info("当前用户ID: {}", userId);
            
            // 提交修改
            boolean success = expertService.submitRevision(requestId, userId, revisionDTO);
            
            if (success) {
                log.info("专家提交修改成功，案例ID: {}", requestId);
                return Result.success("提交修改成功");
            } else {
                log.warn("专家提交修改失败，案例ID: {}", requestId);
                return Result.error("提交修改失败");
            }
        } catch (Exception e) {
            log.error("提交修改失败，案例ID: {}", requestId, e);
            return Result.error("提交修改失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取案例修改历史记录
     * @param requestId 案例ID
     * @return 案例修改历史记录列表
     */
    @GetMapping("/cases/{requestId}/history")
    @ApiOperation("获取案例修改历史记录")
    public Result<List<RevisionHistoryVO>> getRevisionHistory(@PathVariable("requestId") String requestId) {
        try {
            log.info("收到获取案例修改历史记录请求，案例ID: {}", requestId);
            
            // 从token中获取用户ID
            Long userId = BaseContext.getCurrentId();
            if (userId == null) {
                log.warn("用户未登录");
                return Result.error("用户未登录");
            }
            
            log.info("当前用户ID: {}", userId);
            
            // 获取案例修改历史记录
            List<RevisionHistoryVO> revisionHistory = expertService.getRevisionHistory(requestId);
            
            log.info("成功获取案例修改历史记录，案例ID: {}，共{}条记录", requestId, revisionHistory.size());
            return Result.success(revisionHistory);
        } catch (Exception e) {
            log.error("获取案例修改历史记录失败，案例ID: {}", requestId, e);
            return Result.error("获取案例修改历史记录失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取所有被修改过的request_id
     * @return 所有被修改过的request_id列表
     */
    @GetMapping("/modified-request-ids")
    @ApiOperation("获取所有被修改过的request_id")
    public Result<List<String>> getAllModifiedRequestIds() {
        try {
            log.info("收到获取所有被修改过的request_id请求");
            
            // 从token中获取用户ID
            Long userId = BaseContext.getCurrentId();
            if (userId == null) {
                log.warn("用户未登录");
                return Result.error("用户未登录");
            }
            
            log.info("当前用户ID: {}", userId);
            
            // 获取所有被修改过的request_id
            List<String> modifiedRequestIds = expertService.getAllModifiedRequestIds();
            
            log.info("成功获取所有被修改过的request_id，共{}个", modifiedRequestIds.size());
            return Result.success(modifiedRequestIds);
        } catch (Exception e) {
            log.error("获取所有被修改过的request_id失败", e);
            return Result.error("获取所有被修改过的request_id失败: " + e.getMessage());
        }
    }
    
    /**
     * 获取指定request_id的数据修改前后对比
     * @param requestId 请求ID
     * @return 数据修改前后对比
     */
    @GetMapping("/request-data-comparison/{requestId}")
    @ApiOperation("获取指定request_id的数据修改前后对比")
    public Result<RequestDataComparisonVO> getRequestDataComparison(@PathVariable("requestId") String requestId) {
        try {
            log.info("收到获取request_id数据修改前后对比请求，requestId: {}", requestId);
            
            // 从token中获取用户ID
            Long userId = BaseContext.getCurrentId();
            if (userId == null) {
                log.warn("用户未登录");
                return Result.error("用户未登录");
            }
            
            log.info("当前用户ID: {}", userId);
            
            // 获取数据修改前后对比
            RequestDataComparisonVO comparisonVO = expertService.getRequestDataComparison(requestId);
            
            log.info("成功获取request_id数据修改前后对比，requestId: {}", requestId);
            return Result.success(comparisonVO);
        } catch (Exception e) {
            log.error("获取request_id数据修改前后对比失败，requestId: {}", requestId, e);
            return Result.error("获取request_id数据修改前后对比失败: " + e.getMessage());
        }
    }
}