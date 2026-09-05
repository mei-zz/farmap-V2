package com.mei.zhgy.util;

import ai.onnxruntime.*;
import lombok.extern.slf4j.Slf4j;
import nu.pattern.OpenCV;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;

/**
 * CLIP模型工具类，用于提取图片特征向量
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "farmap.ai.legacy-java-clip.enabled", havingValue = "true")
public class CLIPModelUtil {

    private OrtEnvironment env;
    private OrtSession session;

    // CLIP模型参数
    private static final int INPUT_WIDTH = 224;
    private static final int INPUT_HEIGHT = 224;
    private static final float[] MEAN = {0.48145466f, 0.4578275f, 0.40821073f};
    private static final float[] STD = {0.26862954f, 0.26130258f, 0.27577711f};

    // 静态块加载OpenCV
    static {
        try {
            OpenCV.loadLocally();
            log.info("OpenCV库静态加载成功");
        } catch (Exception e) {
            log.error("OpenCV静态加载失败", e);
        }
    }

    @PostConstruct
    public void init() {
        try {
            // 确保OpenCV已加载
            if (System.getProperty("java.library.path") == null ||
                    !System.getProperty("java.library.path").contains("opencv")) {
                OpenCV.loadLocally();
                log.info("OpenCV库动态加载成功");
            }

            // 加载模型文件
            ClassPathResource resource = new ClassPathResource("models/clip-image-encoder.onnx");

            // 初始化ONNX Runtime
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            // Keep local inference stable on developer machines with limited native memory.
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);

            // 从输入流加载模型
            try (InputStream is = resource.getInputStream()) {
                session = env.createSession(is.readAllBytes(), options);
            }

            log.info("CLIP模型加载成功");
        } catch (Exception e) {
            log.error("CLIP模型初始化失败", e);
            // 不抛出：避免大体积 CLIP ONNX/native 内存导致启动期失败拖垮整个应用；session 保持 null，extractImageEmbedding 会优雅返回 null。
        }
    }

    /** Exposes readiness without exposing the native session object. */
    public boolean isReady() {
        return session != null;
    }


    /**
     * 提取图片特征向量
     */
    public String extractImageEmbedding(String imagePath) {
        if (session == null) {
            log.error("CLIP模型未初始化");
            return null;
        }

        Mat image = null;
        Mat processedImage = null;

        try {
            image = loadImage(imagePath);
            if (image.empty()) {
                log.error("无法加载图片: {}", imagePath);
                return null;
            }
            
            log.debug("加载图片成功: {}, 尺寸: {}x{}", imagePath, image.width(), image.height());

            processedImage = preprocessImage(image);
            float[][][][] inputTensor = convertToTensor(processedImage);
            
            // 准备输入数据
            long[] inputShape = {1, 3, INPUT_HEIGHT, INPUT_WIDTH};
            float[] flatTensor = flattenTensor(inputTensor);

            // 执行推理
            float[] embedding = runInference(flatTensor, inputShape);
            float[] normalizedEmbedding = normalizeVector(embedding);
            
            log.debug("成功提取图片特征向量，维度: {}", normalizedEmbedding.length);

            // 转换为Base64
            return encodeEmbedding(normalizedEmbedding);

        } catch (Exception e) {
            log.error("处理图片时发生错误: {}", imagePath, e);
            return null;
        } finally {
            if (image != null) image.release();
            if (processedImage != null) processedImage.release();
        }
    }

    private Mat loadImage(String imagePath) throws IOException {
        if (imagePath.startsWith("http://") || imagePath.startsWith("https://")) {
            try (InputStream in = new URL(imagePath).openStream()) {
                byte[] imageData = in.readAllBytes();
                if (imageData.length == 0) {
                    log.error("从URL加载的图片数据为空: {}", imagePath);
                    return new Mat(); // 返回空的Mat对象
                }
                
                MatOfByte matOfByte = new MatOfByte(imageData);
                Mat image = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);
                matOfByte.release();
                
                if (image.empty()) {
                    log.error("无法解码网络图片: {}", imagePath);
                    return new Mat(); // 返回空的Mat对象
                }
                
                log.debug("成功加载网络图片，尺寸: {}x{}", image.width(), image.height());
                return image;
            } catch (Exception e) {
                log.error("加载网络图片时发生错误: {}", imagePath, e);
                return new Mat(); // 返回空的Mat对象
            }
        } else {
            Mat image = Imgcodecs.imread(imagePath);
            if (image.empty()) {
                log.error("无法加载本地图片: {}", imagePath);
            } else {
                log.debug("成功加载本地图片，尺寸: {}x{}", image.width(), image.height());
            }
            return image;
        }
    }

    private Mat preprocessImage(Mat image) {
        Mat rgbImage = new Mat();
        Imgproc.cvtColor(image, rgbImage, Imgproc.COLOR_BGR2RGB);

        Mat resizedImage = new Mat();
        Imgproc.resize(rgbImage, resizedImage, new Size(INPUT_WIDTH, INPUT_HEIGHT));

        Mat floatImage = new Mat();
        resizedImage.convertTo(floatImage, CvType.CV_32FC3, 1.0 / 255.0);

        // 标准化处理
        Mat normalizedImage = new Mat();
        Core.subtract(floatImage, new Scalar(MEAN[0], MEAN[1], MEAN[2]), normalizedImage);
        Core.divide(normalizedImage, new Scalar(STD[0], STD[1], STD[2]), normalizedImage);

        // 释放资源
        rgbImage.release();
        resizedImage.release();
        floatImage.release();

        return normalizedImage;
    }

    private float[][][][] convertToTensor(Mat image) {
        float[][][][] tensor = new float[1][3][INPUT_HEIGHT][INPUT_WIDTH];

        for (int c = 0; c < 3; c++) {
            for (int h = 0; h < INPUT_HEIGHT; h++) {
                for (int w = 0; w < INPUT_WIDTH; w++) {
                    tensor[0][c][h][w] = (float) image.get(h, w)[c];
                }
            }
        }

        return tensor;
    }

    private float[] flattenTensor(float[][][][] tensor) {
        int size = tensor.length * tensor[0].length * tensor[0][0].length * tensor[0][0][0].length;
        float[] flat = new float[size];
        int index = 0;

        for (float[][][] batch : tensor) {
            for (float[][] channel : batch) {
                for (float[] row : channel) {
                    System.arraycopy(row, 0, flat, index, row.length);
                    index += row.length;
                }
            }
        }

        return flat;
    }

    private float[] runInference(float[] inputData, long[] inputShape) throws OrtException {
        // 创建输入张量
        OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), inputShape);

        // 执行推理
        try (OrtSession.Result results = session.run(Collections.singletonMap("image", inputTensor))) {
            OnnxValue resultValue = results.get(0);
            Object value = resultValue.getValue();
            
            // 处理可能的二维数组情况
            if (value instanceof float[][]) {
                float[][] array2D = (float[][]) value;
                if (array2D.length > 0) {
                    return array2D[0];
                } else {
                    return new float[0];
                }
            } else if (value instanceof float[]) {
                return (float[]) value;
            } else {
                log.error("模型输出类型不支持: {}", value.getClass().getName());
                return new float[0];
            }
        } finally {
            inputTensor.close();
        }
    }

    /**
     * 归一化向量（L2归一化）
     */
    private float[] normalizeVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            log.warn("尝试归一化空向量");
            return new float[0];
        }
        
        float norm = 0.0f;
        for (float value : vector) {
            norm += value * value;
        }
        
        // 防止除零错误
        if (norm == 0.0f) {
            log.warn("向量模长为0，返回原向量");
            return vector.clone();
        }
        
        norm = (float) Math.sqrt(norm);
        
        // 防止归一化时出现异常值
        if (Float.isNaN(norm) || Float.isInfinite(norm)) {
            log.warn("向量模长为NaN或无穷大，返回原向量");
            return vector.clone();
        }

        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
            
            // 确保归一化后的值在合理范围内
            if (Float.isNaN(normalized[i]) || Float.isInfinite(normalized[i])) {
                log.warn("归一化过程中出现NaN或无穷大值，使用原值");
                normalized[i] = vector[i];
            }
        }

        return normalized;
    }

    private String encodeEmbedding(float[] embedding) {
        // 手动转换float数组到double数组
        double[] doubleEmbedding = new double[embedding.length];
        for (int i = 0; i < embedding.length; i++) {
            doubleEmbedding[i] = (double) embedding[i];
        }

        String embeddingStr = Arrays.toString(doubleEmbedding);
        return Base64.getEncoder().encodeToString(embeddingStr.getBytes());
    }

    /**
     * 计算两个向量的余弦相似度
     */
    public double cosineSimilarity(double[] vector1, double[] vector2) {
        if (vector1.length != vector2.length) {
            throw new IllegalArgumentException("向量维度不一致");
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            norm1 += Math.pow(vector1[i], 2);
            norm2 += Math.pow(vector2[i], 2);
        }

        if (norm1 == 0 || norm2 == 0) {
            return 0;
        }

        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    /**
     * 将Base64编码的向量解码为double数组
     */
    public double[] decodeEmbedding(String base64Embedding) {
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(base64Embedding);
            String embeddingStr = new String(decodedBytes);

            // 移除首尾的方括号
            embeddingStr = embeddingStr.substring(1, embeddingStr.length() - 1);

            // 分割并转换为double数组
            String[] parts = embeddingStr.split(", ");
            double[] embedding = new double[parts.length];

            for (int i = 0; i < parts.length; i++) {
                embedding[i] = Double.parseDouble(parts[i]);
            }

            return embedding;
        } catch (Exception e) {
            log.error("解码向量失败", e);
            return new double[0];
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (session != null) session.close();
            if (env != null) env.close();
            log.info("CLIP模型资源已释放");
        } catch (Exception e) {
            log.error("释放资源时出错", e);
        }
    }
}
