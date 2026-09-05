package com.mei.zhgy.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.mei.zhgy.entity.Case;
import com.mei.zhgy.entity.InitialResult;
import com.mei.zhgy.mapper.ExpertMapper;
import com.mei.zhgy.mapper.InitialResultMapper;
import com.mei.zhgy.mapper.UserRequestMapper;
import com.mei.zhgy.service.CaseStorageService;
import com.mei.zhgy.util.CLIPModelUtil;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.*;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 案例存储服务实现类
 */
@Slf4j
@Service
public class CaseStorageServiceImpl implements CaseStorageService {
    
    @Autowired
    private MongoTemplate mongoTemplate;
    
    @Autowired(required = false)
    private MilvusServiceClient milvusClient;
    
    @Autowired(required = false)
    private CLIPModelUtil clipModelUtil;
    
    @Autowired
    private UserRequestMapper userRequestMapper;
    
    @Autowired
    private ExpertMapper expertMapper;
    
    @Autowired
    private InitialResultMapper initialResultMapper;
    
    // Milvus相关常量
    private static final String COLLECTION_NAME = "farmap_image_vectors_new";
    private static final String REQUEST_ID_FIELD = "request_id";
    private static final String EMBEDDING_FIELD = "vector";
    private static final int EMBEDDING_DIM = 512; // Milvus集合定义的维度
    
    // MongoDB相关常量
    private static final String CASE_COLLECTION = "cases";
    
    // MongoDB可用性标志
    private boolean mongoAvailable = true;
    
    /**
     * 初始化Milvus集合
     */
    public void initMilvusCollection() {
        log.info("开始初始化Milvus集合: {}", COLLECTION_NAME);
        
        // 检查milvusClient是否为null
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，跳过集合初始化");
            return;
        }
        
        try {
            // 检查集合是否存在
            R<Boolean> hasCollection = milvusClient.hasCollection(HasCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .build());
            
            if (hasCollection.getData()) {
                log.info("Milvus集合 {} 已存在", COLLECTION_NAME);
            } else {
                log.info("Milvus集合 {} 不存在，开始创建", COLLECTION_NAME);
                
                // 定义字段
                FieldType requestIdField = FieldType.newBuilder()
                        .withName(REQUEST_ID_FIELD)
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .withPrimaryKey(true)
                        .withAutoID(false)
                        .build();
                
                FieldType embeddingField = FieldType.newBuilder()
                        .withName(EMBEDDING_FIELD)
                        .withDataType(DataType.FloatVector)
                        .withDimension(EMBEDDING_DIM)
                        .build();
                
                // 创建集合
                R<RpcStatus> createCollection = milvusClient.createCollection(CreateCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withDescription("存储农事图片向量+request_id，用于RAG检索")
                        .addFieldType(requestIdField)
                        .addFieldType(embeddingField)
                        .build());
                
                if (createCollection.getStatus() != R.Status.Success.getCode()) {
                    log.error("创建Milvus集合失败: {}", createCollection.getMessage());
                    return;
                }
                log.info("Milvus集合 {} 创建成功", COLLECTION_NAME);
            }
            
            // 检查索引是否存在
            try {
                R<DescribeIndexResponse> indexResponse = milvusClient.describeIndex(DescribeIndexParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .build());
                
                if (indexResponse.getStatus() != R.Status.Success.getCode()) {
                    log.info("Milvus集合索引不存在，开始创建索引");
                    
                    // 创建索引
                    R<RpcStatus> createIndex = milvusClient.createIndex(CreateIndexParam.newBuilder()
                            .withCollectionName(COLLECTION_NAME)
                            .withFieldName(EMBEDDING_FIELD)
                            .withIndexType(IndexType.IVF_FLAT)
                            .withMetricType(MetricType.L2)
                            .withExtraParam("{\"nlist\": 128}")
                            .withSyncMode(Boolean.TRUE)
                            .build());
                    
                    if (createIndex.getStatus() != R.Status.Success.getCode()) {
                        log.error("创建Milvus索引失败: {}", createIndex.getMessage());
                        return;
                    }
                    log.info("Milvus索引创建成功");
                } else {
                    log.info("Milvus集合索引已存在");
                }
            } catch (Exception e) {
                log.warn("检查索引时发生异常，可能索引不存在: {}", e.getMessage());
                // 创建索引
                R<RpcStatus> createIndex = milvusClient.createIndex(CreateIndexParam.newBuilder()
                        .withCollectionName(COLLECTION_NAME)
                        .withFieldName(EMBEDDING_FIELD)
                        .withIndexType(IndexType.IVF_FLAT)
                        .withMetricType(MetricType.L2)
                        .withExtraParam("{\"nlist\": 128}")
                        .withSyncMode(Boolean.TRUE)
                        .build());
                
                if (createIndex.getStatus() != R.Status.Success.getCode()) {
                    log.error("创建Milvus索引失败: {}", createIndex.getMessage());
                    return;
                }
                log.info("Milvus索引创建成功");
            }
            
            // 加载集合到内存
            R<RpcStatus> loadCollection = milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .build());
            
            if (loadCollection.getStatus() != R.Status.Success.getCode()) {
                log.error("加载Milvus集合失败: {}", loadCollection.getMessage());
                return;
            }
            log.info("Milvus集合加载成功");
            
        } catch (Exception e) {
            log.error("初始化Milvus集合失败", e);
        }
    }
    
    @Override
    public boolean saveConsensusCase(String requestId) {
        log.info("开始保存达成共识的案例，案例ID: {}", requestId);
        
        // 检查milvusClient是否为null
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，跳过案例保存");
            return false;
        }
        
        try {
            // 1. 从数据库获取案例数据
            // 获取用户请求信息
            com.mei.zhgy.entity.UserRequest userRequest = userRequestMapper.getByRequestId(requestId);
            if (userRequest == null) {
                log.warn("未找到用户请求信息，案例ID: {}", requestId);
                return false;
            }
            
            // 获取初始结果信息
            InitialResult initialResult = initialResultMapper.getByRequestId(requestId);
            if (initialResult == null) {
                log.warn("未找到初始结果信息，案例ID: {}", requestId);
                return false;
            }
            
            // 获取专家修改记录列表
            List<com.mei.zhgy.entity.ExpertRevision> revisionList = expertMapper.getExpertRevisionsByRequestId(requestId);
            if (CollectionUtils.isEmpty(revisionList)) {
                log.warn("未找到专家修改记录，案例ID: {}", requestId);
                return false;
            }
            
            // 2. 构建案例实体
            Case caseEntity = Case.builder()
                    .requestId(requestId)
                    .initialJson(initialResult.getJsonData())
                    .finalJson(getLatestRevisionJson(revisionList))
                    .revisionCount(revisionList.size())
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            
            // 3. 从最终JSON中提取关键信息
            extractKeyInfoFromJson(caseEntity);
            
            // 4. 保存到MongoDB
            Case savedCase = mongoTemplate.save(caseEntity);
            log.info("案例保存到MongoDB成功，案例ID: {}", requestId);
            
            // 5. 保存特征向量到Milvus（这里使用模拟数据，实际应从图片中提取特征向量）
            saveEmbeddingToMilvus(requestId, savedCase.getId().toString());
            
            return true;
        } catch (Exception e) {
            log.error("保存达成共识的案例失败，案例ID: {}", requestId, e);
            return false;
        }
    }
    
    /**
     * 获取最新的专家修改JSON
     */
    private String getLatestRevisionJson(List<com.mei.zhgy.entity.ExpertRevision> revisionList) {
        // 按修改时间排序，获取最新的修改
        revisionList.sort((r1, r2) -> r2.getRevisionTime().compareTo(r1.getRevisionTime()));
        return revisionList.get(0).getRevisedJson();
    }
    
    /**
     * 从最终JSON中提取关键信息
     */
    private void extractKeyInfoFromJson(Case caseEntity) {
        try {
            // 检查finalJson是否为有效的JSON格式
            if (caseEntity.getFinalJson() == null || !caseEntity.getFinalJson().trim().startsWith("{")) {
                log.warn("Final JSON不是有效的JSON格式，跳过解析: {}", caseEntity.getFinalJson());
                return;
            }
            
            JSONObject jsonObject = JSONObject.parseObject(caseEntity.getFinalJson());
            
            // 提取树种信息
            JSONObject treeSpeciesObj = jsonObject.getJSONObject("树种识别");
            if (treeSpeciesObj != null) {
                caseEntity.setTreeSpecies(treeSpeciesObj.getString("种类"));
            }
            
            // 提取病害信息
            JSONObject diseaseObj = jsonObject.getJSONObject("病虫害诊断");
            if (diseaseObj != null) {
                String suspectedDisease = diseaseObj.getString("疑似病害");
                if (suspectedDisease != null) {
                    caseEntity.setDiseaseType(suspectedDisease);
                }
                
                String suspectedPest = diseaseObj.getString("虫害迹象");
                if (suspectedPest != null) {
                    caseEntity.setPestType(suspectedPest);
                }
            }
            
            // 提取营养状况
            JSONObject nutritionObj = jsonObject.getJSONObject("营养状况诊断");
            if (nutritionObj != null) {
                // 合并所有营养元素状态信息
                StringBuilder nutritionStatus = new StringBuilder();
                String nitrogenStatus = nutritionObj.getString("氮素状态");
                String phosphorusStatus = nutritionObj.getString("磷素状态");
                String potassiumStatus = nutritionObj.getString("钾素状态");
                String microElementStatus = nutritionObj.getString("中微量元素");
                
                if (nitrogenStatus != null) {
                    nutritionStatus.append("氮素: ").append(nitrogenStatus).append("; ");
                }
                if (phosphorusStatus != null) {
                    nutritionStatus.append("磷素: ").append(phosphorusStatus).append("; ");
                }
                if (potassiumStatus != null) {
                    nutritionStatus.append("钾素: ").append(potassiumStatus).append("; ");
                }
                if (microElementStatus != null) {
                    nutritionStatus.append("中微量元素: ").append(microElementStatus).append("; ");
                }
                
                if (nutritionStatus.length() > 0) {
                    // 移除末尾的分号和空格
                    caseEntity.setNutritionStatus(nutritionStatus.toString().trim().replaceAll("; $", ""));
                }
            }
        } catch (Exception e) {
            log.warn("解析JSON提取关键信息失败", e);
        }
    }
    
    /**
     * 保存特征向量到Milvus
     */
    private void saveEmbeddingToMilvus(String requestId, String caseId) {
        try {
            // 从MongoDB获取案例信息
            Case caseEntity = mongoTemplate.findOne(Query.query(Criteria.where("requestId").is(requestId)), Case.class);
            if (caseEntity == null) {
                log.error("未找到案例，requestId: {}", requestId);
                return;
            }
            
            // 获取图片URL (从initialJson中提取)
            String imageUrl = extractImageUrlFromJson(caseEntity.getInitialJson());
            if (imageUrl == null || imageUrl.isEmpty()) {
                log.error("案例中没有图片URL，requestId: {}", requestId);
                return;
            }
            
            // 使用CLIP模型提取图片特征向量
            if (clipModelUtil == null) {
                log.warn("旧 Java CLIP 已禁用，案例写入需要迁移到 Local AI provider");
                return;
            }
            String embeddingBase64 = clipModelUtil.extractImageEmbedding(imageUrl);
            if (embeddingBase64 == null || embeddingBase64.isEmpty()) {
                log.error("提取图片特征向量失败，图片URL: {}", imageUrl);
                return;
            }
            
            // 解析Base64编码的向量
            List<Float> embedding = parseEmbeddingFromString(embeddingBase64);
            if (embedding.isEmpty()) {
                log.error("解析特征向量失败，requestId: {}", requestId);
                return;
            }
            
            // 保存向量到Milvus
            saveEmbeddingToMilvus(requestId, embedding);
        } catch (Exception e) {
            log.error("保存特征向量到Milvus时发生错误，案例ID: {}", requestId, e);
        }
    }
    
    /**
     * 从JSON中提取图片URL
     * @param initialJson 初始JSON数据
     * @return 图片URL
     */
    private String extractImageUrlFromJson(String initialJson) {
        if (initialJson == null || initialJson.isEmpty()) {
            return null;
        }
        
        try {
            JSONObject jsonObject = JSONObject.parseObject(initialJson);
            return jsonObject.getString("image_url");
        } catch (Exception e) {
            log.error("解析初始JSON提取图片URL失败: {}", initialJson, e);
            return null;
        }
    }
    
    /**
     * 将特征向量保存到Milvus
     * @param requestId 案例请求ID
     * @param embedding 特征向量
     */
    private void saveEmbeddingToMilvus(String requestId, List<Float> embedding) {
        try {
            log.debug("准备保存特征向量到Milvus，案例ID: {}, 向量维度: {}", requestId, embedding.size());
            
            // 验证向量
            if (embedding.isEmpty()) {
                log.error("特征向量为空，无法保存到Milvus，案例ID: {}", requestId);
                return;
            }
            
            // 检查向量值是否有效
            for (int i = 0; i < embedding.size(); i++) {
                Float value = embedding.get(i);
                if (value.isNaN() || value.isInfinite()) {
                    log.error("特征向量包含无效值，位置: {}, 值: {}, 案例ID: {}", i, value, requestId);
                    return;
                }
            }
            
            // 构建插入数据
            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field(REQUEST_ID_FIELD, Arrays.asList(requestId)));
            fields.add(new InsertParam.Field(EMBEDDING_FIELD, Arrays.asList(embedding)));
            
            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withFields(fields)
                    .build();
            
            R<MutationResult> insertResult = milvusClient.insert(insertParam);
            if (insertResult.getStatus() == R.Status.Success.getCode()) {
                log.info("案例特征向量已保存到Milvus，案例ID: {}", requestId);
            } else {
                log.error("保存案例特征向量到Milvus失败，案例ID: {}, 状态码: {}, 错误信息: {}", 
                         requestId, insertResult.getStatus(), insertResult.getMessage());
            }
        } catch (Exception e) {
            log.error("保存特征向量到Milvus时发生错误，案例ID: {}", requestId, e);
        }
    }
    
    /**
     * 解析Base64编码的向量字符串
     * @param embeddingBase64 Base64编码的向量字符串
     * @return 浮点数向量列表
     */
    private List<Float> parseEmbeddingFromString(String embeddingBase64) {
        try {
            if (embeddingBase64 == null || embeddingBase64.isEmpty()) {
                return Collections.emptyList();
            }
            
            byte[] decodedBytes = Base64.getDecoder().decode(embeddingBase64);
            String embeddingStr = new String(decodedBytes);
            embeddingStr = embeddingStr.replaceAll("[\\[\\]]", ""); // 移除方括号
            String[] values = embeddingStr.split(",");
            
            List<Float> embedding = new ArrayList<>();
            for (String value : values) {
                embedding.add(Float.valueOf(value.trim()));
            }
            return embedding;
        } catch (Exception e) {
            log.error("解析向量字符串失败: {}", embeddingBase64, e);
            return Collections.emptyList();
        }
    }
    
    @Override
    public List<Case> searchSimilarCases(List<Float> imageEmbedding, int limit) {
        // 检查milvusClient是否为null
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，无法搜索相似案例");
            return Collections.emptyList();
        }
        
        try {
            // 检查向量是否为空
            if (imageEmbedding == null || imageEmbedding.isEmpty()) {
                log.warn("图片特征向量为空，跳过相似案例检索");
                return Collections.emptyList();
            }
            if (!validateVectorDimension(imageEmbedding.size())) return Collections.emptyList();
            
            // 在Milvus中搜索相似的图片特征向量
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withMetricType(MetricType.L2)
                    .withOutFields(Arrays.asList(REQUEST_ID_FIELD))
                    .withTopK(limit)
                    .withVectors(Collections.singletonList(imageEmbedding))
                    .withVectorFieldName(EMBEDDING_FIELD)
                    .withParams("{\"nprobe\": 10}")
                    .build();
            
            R<SearchResults> searchResults = milvusClient.search(searchParam);
            
            if (searchResults.getStatus() != R.Status.Success.getCode()) {
                log.error("Milvus搜索失败，状态码: {}，原因: {}", searchResults.getStatus(), searchResults.getMessage());
                return Collections.emptyList();
            }
            
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
            
            // 根据requestId从MongoDB获取完整案例信息
            List<Case> similarCases = new ArrayList<>();
            for (SearchResultsWrapper.IDScore idScore : idScores) {
                String requestId = (String) idScore.get(REQUEST_ID_FIELD);
                Case caseEntity = mongoTemplate.findOne(Query.query(Criteria.where("requestId").is(requestId)), Case.class);
                if (caseEntity != null) {
                    similarCases.add(caseEntity);
                }
            }
            
            log.info("检索到{}个相似案例", similarCases.size());
            return similarCases;
        } catch (Exception e) {
            log.error("检索相似案例时发生错误", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 搜索相似案例并返回相似度分数
     * @param imageEmbedding 当前图片的特征向量
     * @param limit 返回案例数量限制
     * @return 包含案例和相似度分数的列表
     */
    @Override
    public List<SimilarCaseWithScore> searchSimilarCasesWithScore(List<Float> imageEmbedding, int limit) {
        // 检查milvusClient是否为null
        if (milvusClient == null) {
            log.warn("Milvus客户端未初始化，无法搜索相似案例");
            return Collections.emptyList();
        }

        try {
            // 检查向量是否为空
            if (imageEmbedding == null || imageEmbedding.isEmpty()) {
                log.warn("图片特征向量为空，跳过相似案例检索");
                return Collections.emptyList();
            }
            if (!validateVectorDimension(imageEmbedding.size())) return Collections.emptyList();
            
            // 检查向量值是否有效
            if (imageEmbedding == null || imageEmbedding.isEmpty()) {
                log.warn("图片特征向量为空，跳过相似案例检索");
                return Collections.emptyList();
            }
            
            // 检查向量值是否有效
            for (int i = 0; i < imageEmbedding.size(); i++) {
                Float value = imageEmbedding.get(i);
                if (value.isNaN() || value.isInfinite()) {
                    log.error("查询向量包含无效值，位置: {}, 值: {}", i, value);
                    return Collections.emptyList();
                }
            }
            
            log.debug("开始在Milvus中搜索相似案例，向量维度: {}, 限制数量: {}", imageEmbedding.size(), limit);
            
            // 在Milvus中搜索相似的图片特征向量
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME)
                    .withMetricType(MetricType.L2)
                    .withOutFields(Arrays.asList(REQUEST_ID_FIELD))
                    .withTopK(limit)
                    .withVectors(Collections.singletonList(imageEmbedding))
                    .withVectorFieldName(EMBEDDING_FIELD)
                    .withParams("{\"nprobe\": 10}")
                    .build();
            
            R<SearchResults> searchResults = milvusClient.search(searchParam);
            
            if (searchResults.getStatus() != R.Status.Success.getCode()) {
                log.error("Milvus搜索失败，状态码: {}，原因: {}", searchResults.getStatus(), searchResults.getMessage());
                return Collections.emptyList();
            }
            
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
            
            log.debug("Milvus搜索返回 {} 个结果", idScores.size());
            
            // 根据requestId从MongoDB获取完整案例信息并附带相似度分数
            List<SimilarCaseWithScore> similarCasesWithScore = new ArrayList<>();
            for (SearchResultsWrapper.IDScore idScore : idScores) {
                String requestId = (String) idScore.get(REQUEST_ID_FIELD);
                Case caseEntity = mongoTemplate.findOne(Query.query(Criteria.where("requestId").is(requestId)), Case.class);
                if (caseEntity != null) {
                    double distance = idScore.getScore();
                    double rankingScore = 1D / (1D + Math.max(0D, distance));
                    similarCasesWithScore.add(new SimilarCaseWithScore(caseEntity, distance, rankingScore));
                } else {
                    log.debug("未找到对应的案例信息，案例ID: {}", requestId);
                }
            }
            
            log.info("检索到{}个相似度大于70%的相似案例", similarCasesWithScore.size());
            return similarCasesWithScore;
        } catch (Exception e) {
            log.error("检索相似案例时发生错误", e);
            return Collections.emptyList();
        }
    }

    private boolean validateVectorDimension(int actualDimension) {
        if (actualDimension != EMBEDDING_DIM) {
            log.error("VECTOR_DIMENSION_MISMATCH: query={}, expected={}", actualDimension, EMBEDDING_DIM);
            return false;
        }
        try {
            R<DescribeCollectionResponse> response = milvusClient.describeCollection(DescribeCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_NAME).build());
            if (response.getStatus() != R.Status.Success.getCode()) return false;
            int collectionDimension = response.getData().getSchema().getFieldsList().stream()
                    .filter(field -> EMBEDDING_FIELD.equals(field.getName()))
                    .flatMap(field -> field.getTypeParamsList().stream())
                    .filter(param -> "dim".equals(param.getKey()))
                    .mapToInt(param -> Integer.parseInt(param.getValue())).findFirst().orElse(0);
            if (collectionDimension != actualDimension) {
                log.error("VECTOR_DIMENSION_MISMATCH: query={}, collection={}", actualDimension, collectionDimension);
                return false;
            }
            return true;
        } catch (Exception error) {
            log.warn("Milvus collection schema unavailable: {}", error.getClass().getSimpleName());
            return false;
        }
    }
}
