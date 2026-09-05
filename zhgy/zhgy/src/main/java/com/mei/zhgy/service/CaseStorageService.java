package com.mei.zhgy.service;

import com.mei.zhgy.entity.Case;

import java.util.List;

/**
 * 案例存储服务接口
 */
public interface CaseStorageService {
    
    /**
     * 封装包含相似度分数的案例信息
     */
    class SimilarCaseWithScore {
        private Case caseEntity;
        private double similarityScore;

        public SimilarCaseWithScore(Case caseEntity, double similarityScore) {
            this.caseEntity = caseEntity;
            this.similarityScore = similarityScore;
        }

        public Case getCaseEntity() {
            return caseEntity;
        }

        public double getSimilarityScore() {
            return similarityScore;
        }
    }
    
    /**
     * 保存达成共识的案例到知识库
     * @param requestId 案例ID
     * @return 是否保存成功
     */
    boolean saveConsensusCase(String requestId);
    
    /**
     * 根据图片特征向量检索相似案例
     * @param imageEmbedding 图片特征向量
     * @param limit 返回案例数量限制
     * @return 相似案例列表
     */
    List<Case> searchSimilarCases(List<Float> imageEmbedding, int limit);
    
    /**
     * 搜索相似案例并返回相似度分数
     * @param imageEmbedding 当前图片的特征向量
     * @param limit 返回案例数量限制
     * @return 包含案例和相似度分数的列表
     */
    List<SimilarCaseWithScore> searchSimilarCasesWithScore(List<Float> imageEmbedding, int limit);
}