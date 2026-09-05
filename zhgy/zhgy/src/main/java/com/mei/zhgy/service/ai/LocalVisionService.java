package com.mei.zhgy.service.ai;

import com.mei.zhgy.util.CLIPModelUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 复用项目已有的 CLIP ONNX，不让相似图像检索依赖百炼。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "farmap.ai.legacy-java-clip.enabled", havingValue = "true")
public class LocalVisionService implements EmbeddingProvider {
    private final CLIPModelUtil clipModelUtil;

    @Value("${farmap.ai.local-vision.enabled:true}")
    private boolean enabled;

    public LocalVisionService(CLIPModelUtil clipModelUtil) {
        this.clipModelUtil = clipModelUtil;
    }

    @Override
    public EmbeddingResult embedImage(String imageUrl) {
        long start = System.currentTimeMillis();
        if (!enabled || !StringUtils.hasText(imageUrl)) return empty(start);
        String encoded = clipModelUtil.extractImageEmbedding(imageUrl);
        if (!StringUtils.hasText(encoded)) return empty(start);
        double[] decoded = clipModelUtil.decodeEmbedding(encoded);
        if (decoded.length == 0) return empty(start);
        List<Float> vector = new ArrayList<>(decoded.length);
        for (double value : decoded) vector.add((float) value);
        return new EmbeddingResult(vector, "local", "clip-image-encoder.onnx", decoded.length,
                System.currentTimeMillis() - start);
    }

    /**
     * Text embedding is intentionally not guessed from the image encoder.
     * The offline BGE asset was verified in the user's llm environment, but
     * its Python adapter is not started automatically by the Spring service.
     */
    @Override
    public EmbeddingResult embedText(String text) {
        return new EmbeddingResult(Collections.emptyList(), "local", "BAAI/bge-small-zh-v1.5", 0, 0);
    }

    private EmbeddingResult empty(long start) {
        return new EmbeddingResult(Collections.emptyList(), "local", "clip-image-encoder.onnx", 0,
                System.currentTimeMillis() - start);
    }
}
