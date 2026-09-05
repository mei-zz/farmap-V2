package com.mei.zhgy.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 案例实体类，用于存储专家修正后的案例
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "cases")
public class Case implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * MongoDB主键
     */
    @Id
    private ObjectId id;
    
    /**
     * 案例ID（与user_requests表中的request_id对应）
     */
    @Field("request_id")
    private String requestId;
    
    /**
     * 图片特征向量（Base64编码）
     */
    @Field("image_embedding")
    private String imageEmbedding;
    
    /**
     * 初始模型输出的JSON数据
     */
    @Field("initial_json")
    private String initialJson;
    
    /**
     * 专家共识数据（最终JSON）
     */
    @Field("final_json")
    private String finalJson;
    
    /**
     * 专家修改次数
     */
    @Field("revision_count")
    private Integer revisionCount;
    
    /**
     * 案例创建时间
     */
    @Field("create_time")
    private LocalDateTime createTime;
    
    /**
     * 案例更新时间
     */
    @Field("update_time")
    private LocalDateTime updateTime;
    
    /**
     * 树种信息
     */
    @Field("tree_species")
    private String treeSpecies;
    
    /**
     * 病害类型
     */
    @Field("disease_type")
    private String diseaseType;
    
    /**
     * 虫害类型
     */
    @Field("pest_type")
    private String pestType;
    
    /**
     * 营养状况
     */
    @Field("nutrition_status")
    private String nutritionStatus;
}