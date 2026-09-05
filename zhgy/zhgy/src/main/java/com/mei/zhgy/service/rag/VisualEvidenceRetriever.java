package com.mei.zhgy.service.rag;

import com.mei.zhgy.dto.DiagnosisAnalyzeRequest;
import com.mei.zhgy.vo.DiagnosisEvidenceVO;
import com.mei.zhgy.service.ai.EmbeddingProvider;
import com.mei.zhgy.service.ai.ImageEmbeddingProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Camera Retriever：复用已有上传接口产出的 URL，只负责形成统一视觉证据。
 * 真实视觉模型仍由原 /ai-model/analyze 负责，本阶段不重复实现模型服务。
 */
@Component
public class VisualEvidenceRetriever implements DiagnosisRetriever {
    private final ImageEmbeddingProvider embeddingProvider;
    public VisualEvidenceRetriever() { this.embeddingProvider = null; }
    @Autowired public VisualEvidenceRetriever(ImageEmbeddingProvider embeddingProvider) { this.embeddingProvider = embeddingProvider; }
    @Override
    public String modality() {
        return "camera";
    }

    @Override
    public List<DiagnosisEvidenceVO> retrieve(DiagnosisAnalyzeRequest request) {
        if (request.getImageUrls() == null || request.getImageUrls().isEmpty()) {
            return Collections.emptyList();
        }

        List<DiagnosisEvidenceVO> evidence = new ArrayList<>();
        int index = 1;
        for (String imageUrl : request.getImageUrls()) {
            if (imageUrl == null || imageUrl.trim().isEmpty()) {
                continue;
            }
            String id = "camera_" + index;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceMode", "real-input");
            if (index == 1 && embeddingProvider != null) {
                EmbeddingProvider.EmbeddingResult embedding = embeddingProvider.embedImage(imageUrl);
                metadata.put("embeddingProvider", embedding.getProvider());
                metadata.put("embeddingModel", embedding.getModel());
                metadata.put("embeddingDimension", embedding.getDimension());
                metadata.put("embeddingLatencyMs", embedding.getLatencyMs());
            }
            evidence.add(DiagnosisEvidenceVO.builder()
                    .id(id)
                    .modality("camera")
                    .title("Camera 图像输入 " + index)
                    .summary("输入图像已纳入视觉证据集；本阶段由原视觉分析接口负责图像特征判断。")
                    .score(index == 1 ? 0.92 : 0.86)
                    .source(DiagnosisEvidenceVO.Source.builder()
                            .type("camera")
                            .name("Uploaded camera image")
                            .timestamp(request.getWeather() == null ? null : request.getWeather().getObservedAt())
                            .fieldId(request.getFieldId())
                            .cameraId("CAM-" + String.format("%02d", index))
                            .build())
                    .preview(DiagnosisEvidenceVO.Preview.builder().imageUrl(imageUrl).build())
                    .metadata(metadata)
                    .build());
            index++;
        }
        return evidence;
    }
}
