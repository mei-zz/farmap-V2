package com.mei.zhgy.mapper;

import com.mei.zhgy.entity.ExpertRevision;
import com.mei.zhgy.entity.InitialResult;
import com.mei.zhgy.vo.CaseDetailVO;
import com.mei.zhgy.vo.PendingRevisionCaseVO;
import com.mei.zhgy.vo.RevisionHistoryVO;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ExpertMapper {
    
    /**
     * 获取待专家修改的案例列表
     * @param expertId 专家ID
     * @return 案例列表
     */
    @Select("SELECT " +
            "ur.request_id AS requestId, " +
            "ur.upload_time AS uploadTime, " +
            "ur.image_count AS imageCount, " +
            "ir.generate_time AS generateTime, " +
            "COUNT(DISTINCT er.expert_id) AS revisionCount " +
            "FROM user_requests ur " +
            "JOIN initial_results ir ON ur.request_id = ir.request_id " +
            "LEFT JOIN expert_revisions er ON ur.request_id = er.request_id " +
            "WHERE ur.status IN (1, 2) " +
            "AND ur.request_id NOT IN (SELECT request_id FROM expert_revisions WHERE expert_id = #{expertId}) " +
            "GROUP BY ur.request_id, ur.upload_time, ur.image_count, ir.generate_time " +
            "ORDER BY ur.upload_time DESC")
    List<PendingRevisionCaseVO> getPendingRevisionCases(@Param("expertId") Long expertId);
    
    /**
     * 根据requestId获取用户请求信息
     * @param requestId 案例ID
     * @return 用户请求信息
     */
    @Select("SELECT request_id, user_id, image_urls, upload_time, status FROM user_requests WHERE request_id = #{requestId}")
    CaseDetailVO.UserRequestInfo getUserRequestInfo(@Param("requestId") String requestId);
    
    /**
     * 根据requestId获取初始结果信息
     * @param requestId 案例ID
     * @return 初始结果信息
     */
    @Select("SELECT result_id, request_id, json_data, generate_time, model_version FROM initial_results WHERE request_id = #{requestId}")
    InitialResult getInitialResultInfo(@Param("requestId") String requestId);
    
    /**
     * 根据requestId获取专家修改记录列表
     * @param requestId 案例ID
     * @return 专家修改记录列表
     */
    @Select("SELECT " +
            "revision_id AS revisionId, " +
            "expert_id AS expertId, " +
            "revised_json AS revisedJson, " +
            "revision_time AS revisionTime, " +
            "is_agree AS isAgree, " +
            "revision_notes AS revisionNotes " +
            "FROM expert_revisions " +
            "WHERE request_id = #{requestId} " +
            "ORDER BY revision_time DESC")
    List<CaseDetailVO.RevisionRecordVO> getRevisionRecords(@Param("requestId") String requestId);
    
    /**
     * 插入专家修改记录
     * @param revisionId 修改记录ID
     * @param requestId 案例ID
     * @param expertId 专家ID
     * @param revisedJson 修改后的JSON
     * @param isAgree 是否同意上一次修改
     * @param revisionNotes 修改理由
     */
    @Insert("INSERT INTO expert_revisions (revision_id, request_id, expert_id, revised_json, revision_time, is_agree, revision_notes) " +
            "VALUES (#{revisionId}, #{requestId}, #{expertId}, #{revisedJson}, NOW(), #{isAgree}, #{revisionNotes})")
    void insertExpertRevision(@Param("revisionId") String revisionId,
                              @Param("requestId") String requestId,
                              @Param("expertId") Long expertId,
                              @Param("revisedJson") String revisedJson,
                              @Param("isAgree") Integer isAgree,
                              @Param("revisionNotes") String revisionNotes);
    
    /**
     * 更新用户请求状态
     * @param requestId 案例ID
     * @param oldStatus 原状态
     * @param newStatus 新状态
     */
    @Update("UPDATE user_requests SET status = #{newStatus} WHERE request_id = #{requestId} AND status = #{oldStatus}")
    int updateUserRequestStatus(@Param("requestId") String requestId,
                                @Param("oldStatus") Integer oldStatus,
                                @Param("newStatus") Integer newStatus);
    
    /**
     * 获取用户请求的当前状态
     * @param requestId 案例ID
     * @return 当前状态
     */
    @Select("SELECT status FROM user_requests WHERE request_id = #{requestId}")
    Integer getUserRequestStatus(@Param("requestId") String requestId);
    
    /**
     * 根据requestId获取案例修改历史记录（简化版，不包含完整JSON）
     * @param requestId 案例ID
     * @return 案例修改历史记录列表
     */
    @Select("SELECT " +
            "revision_id AS revisionId, " +
            "expert_id AS expertId, " +
            "revision_time AS revisionTime, " +
            "is_agree AS isAgree, " +
            "revision_notes AS revisionNotes " +
            "FROM expert_revisions " +
            "WHERE request_id = #{requestId} " +
            "ORDER BY revision_time DESC")
    List<RevisionHistoryVO> getRevisionHistoryByRequestId(@Param("requestId") String requestId);
    
    /**
     * 统计指定案例的专家修改数量
     * @param requestId 案例ID
     * @return 参与修改的专家数量
     */
    @Select("SELECT COUNT(DISTINCT expert_id) FROM expert_revisions WHERE request_id = #{requestId}")
    int countExpertRevisions(@Param("requestId") String requestId);
    
    /**
     * 获取指定案例的所有修改JSON数据
     * @param requestId 案例ID
     * @return 修改JSON数据列表
     */
    @Select("SELECT revised_json FROM expert_revisions WHERE request_id = #{requestId}")
    List<String> getRevisedJsonList(@Param("requestId") String requestId);
    
    /**
     * 根据requestId获取专家修改记录列表（实体类）
     * @param requestId 案例ID
     * @return 专家修改记录列表
     */
    @Select("SELECT * FROM expert_revisions WHERE request_id = #{requestId} ORDER BY revision_time DESC")
    List<ExpertRevision> getExpertRevisionsByRequestId(@Param("requestId") String requestId);
    
    /**
     * 获取所有被修改过的request_id
     * @return 所有被修改过的request_id列表
     */
    @Select("SELECT request_id FROM expert_revisions GROUP BY request_id ORDER BY MAX(revision_time) DESC")
    List<String> getAllModifiedRequestIds();
    
    /**
     * 根据requestId获取初始结果信息
     * @param requestId 案例ID
     * @return 初始结果信息
     */
    @Select("SELECT result_id, request_id, json_data, generate_time, model_version FROM initial_results WHERE request_id = #{requestId}")
    InitialResult getInitialResultByRequestId(@Param("requestId") String requestId);
}
