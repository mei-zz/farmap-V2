package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.dto.ImageAnalyzeDTO;
import com.mei.zhgy.entity.InitialResult;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.AIModelService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/ai-model")
@Slf4j
@Api(tags = "AI模型接口")
public class AIModelController {
    
    @Autowired
    private AIModelService aiModelService;
    
    @Value("${zhgy.upload.dir:./uploads}")
    private String uploadDir;

    @Value("${zhgy.upload.base-url:http://localhost:24009/uploads}")
    private String uploadBaseUrl;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiOperation("上传待分析的作物图片")
    public Result<List<String>> uploadCropImages(@RequestParam("files") List<MultipartFile> files) {
        try {
            if (files == null || files.isEmpty() || files.size() > 5) {
                return Result.error("请上传1-5张图片");
            }

            Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(root);
            List<String> urls = new ArrayList<>();
            for (MultipartFile file : files) {
                String contentType = file.getContentType();
                if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024L ||
                        !("image/jpeg".equals(contentType) || "image/png".equals(contentType))) {
                    return Result.error("仅支持不超过10MB的 JPG/PNG 图片");
                }
                String extension = "image/png".equals(contentType) ? ".png" : ".jpg";
                String fileName = UUID.randomUUID() + extension;
                Path target = root.resolve(fileName).normalize();
                if (!target.startsWith(root)) {
                    return Result.error("非法文件名");
                }
                Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
                urls.add(uploadBaseUrl.replaceAll("/$", "") + "/" + fileName);
            }
            return Result.success(urls);
        } catch (Exception e) {
            log.error("图片上传失败", e);
            return Result.error("图片上传失败: " + e.getMessage());
        }
    }
    
    /**
     * 处理AI模型请求
     * @param imageAnalyzeDTO 包含图片URL列表的DTO
     * @return 分析结果
     */
    @PostMapping("/analyze")
    @ApiOperation("作物图像智能分析")
    public Result<String> analyzeCropImages(@RequestBody ImageAnalyzeDTO imageAnalyzeDTO) {
        log.info("收到作物图像分析请求");
        log.info("请求参数: {}", imageAnalyzeDTO);
        
        try {
            // 参数校验
            if (imageAnalyzeDTO == null) {
                log.warn("请求参数为空");
                return Result.error("请求参数不能为空");
            }
            
            List<String> imageUrls = imageAnalyzeDTO.getImageUrls();
            log.info("图片URL列表: {}", imageUrls);
            
            if (imageUrls == null || imageUrls.isEmpty()) {
                log.warn("图片URL列表为空");
                return Result.error("请提供图片URL");
            }
            
            if (imageUrls.size() > 5) {
                log.warn("图片数量超过限制: {}", imageUrls.size());
                return Result.error("最多支持5张图片");
            }
            
            log.info("正在处理{}张图片的分析请求", imageUrls.size());
            
            // 获取当前用户ID
            Long userId = BaseContext.getCurrentId();
            log.info("当前用户ID: {}", userId);
            if (userId == null) {
                log.warn("用户未登录，无法获取用户ID");
                return Result.error("用户未登录");
            }
            
            log.info("开始处理AI模型请求，用户ID: {}, 图片数量: {}", userId, imageUrls.size());
            
            // 处理AI模型请求，将API Key传递给service层
            InitialResult initialResult = aiModelService.processAIModelRequest(imageUrls, userId.toString());
            
            if (initialResult == null) {
                log.warn("AI模型处理返回空结果");
                return Result.error("AI模型处理失败");
            }
            
            String jsonData = initialResult.getJsonData();
            log.info("AI模型处理完成，返回数据长度: {}", jsonData != null ? jsonData.length() : 0);
            
            return Result.success(jsonData);
        } catch (Exception e) {
            log.error("作物图像分析失败", e);
            return Result.error("作物图像分析失败: " + e.getMessage());
        }
    }
}
