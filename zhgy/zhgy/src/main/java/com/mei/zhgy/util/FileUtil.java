package com.mei.zhgy.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.util.Base64;
import java.util.UUID;

@Slf4j
public class FileUtil {
    
    private static final String UPLOAD_DIR = "uploads/";
    
    static {
        // 确保上传目录存在
        File dir = new File(UPLOAD_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("创建上传目录: {}", dir.getAbsolutePath());
        }
    }
    
    /**
     * 保存上传的文件
     * @param file 上传的文件
     * @return 文件保存路径
     * @throws IOException IO异常
     */
    public static String saveFile(MultipartFile file) throws IOException {
        return saveFile(file, UPLOAD_DIR);
    }
    
    /**
     * 保存文件到指定目录
     * @param file MultipartFile对象
     * @param directory 目标目录
     * @return 保存后的文件名
     * @throws IOException IO异常
     */
    public static String saveFile(MultipartFile file, String directory) throws IOException {
        log.info("开始保存文件到目录: {}, 文件名: {}, 大小: {}", directory, file.getOriginalFilename(), file.getSize());
        
        // 创建目录
        File dir = new File(directory);
        if (!dir.exists()) {
            dir.mkdirs();
            log.info("创建新目录: {}", dir.getAbsolutePath());
        }
        
        // 生成唯一文件名
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String filename = UUID.randomUUID().toString() + extension;
        
        // 保存文件
        String filepath = directory + File.separator + filename;
        File dest = new File(filepath);
        
        try {
            file.transferTo(dest);
            log.info("文件保存完成，路径: {}, 文件大小: {}", filepath, file.getSize());
            return filepath;
        } catch (IOException e) {
            log.error("保存文件时发生错误，路径: {}", filepath, e);
            throw e;
        }
    }
    
    /**
     * 将MultipartFile转换为Base64字符串
     * @param file MultipartFile对象
     * @return Base64编码的字符串
     * @throws IOException IO异常
     */
    public static String encodeToBase64(MultipartFile file) throws IOException {
        log.info("将MultipartFile转换为Base64，文件名: {}, 大小: {}", file.getOriginalFilename(), file.getSize());
        try {
            byte[] bytes = file.getBytes();
            String base64String = Base64.getEncoder().encodeToString(bytes);
            log.info("转换完成，Base64字符串长度: {}", base64String.length());
            return base64String;
        } catch (IOException e) {
            log.error("转换MultipartFile到Base64时发生错误，文件名: {}", file.getOriginalFilename(), e);
            throw e;
        }
    }
    
    /**
     * 将MultipartFile编码为Base64字符串
     * @param file MultipartFile对象
     * @return Base64编码的字符串
     * @throws IOException IO异常
     */
    public static String encodeMultipartFileToBase64(MultipartFile file) throws IOException {
        return encodeToBase64(file);
    }
    
    /**
     * 将文件编码为Base64字符串
     * @param file 文件
     * @return Base64编码的字符串
     * @throws IOException IO异常
     */
    public static String encodeFileToBase64(File file) throws IOException {
        log.info("将文件转换为Base64，路径: {}, 大小: {}", file.getAbsolutePath(), file.length());
        try {
            byte[] fileContent = Files.readAllBytes(file.toPath());
            String base64String = Base64.getEncoder().encodeToString(fileContent);
            log.info("转换完成，Base64字符串长度: {}", base64String.length());
            return base64String;
        } catch (IOException e) {
            log.error("转换文件到Base64时发生错误，路径: {}", file.getAbsolutePath(), e);
            throw e;
        }
    }
    
    /**
     * 将URL图片转换为Base64字符串
     * @param imageUrl 图片URL
     * @return Base64编码的字符串
     * @throws IOException IO异常
     */
    public static String encodeUrlToBase64(String imageUrl) throws IOException {
        log.info("将URL图片转换为Base64，URL: {}", imageUrl);
        
        try (InputStream inputStream = new URL(imageUrl).openStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int nRead;
            byte[] data = new byte[1024];
            while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            buffer.flush();
            
            byte[] bytes = buffer.toByteArray();
            String base64String = Base64.getEncoder().encodeToString(bytes);
            log.info("URL图片转换完成，Base64字符串长度: {}", base64String.length());
            return base64String;
        } catch (Exception e) {
            log.error("下载或转换URL图片时发生异常: {}", imageUrl, e);
            throw e;
        }
    }
}