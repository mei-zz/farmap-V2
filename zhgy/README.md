# 树木图像分析服务

这是一个基于腾讯混元视觉模型的树木图像分析服务，可以通过API接口接收图片URL并返回分析结果。

## 功能

- 接收1-5张树木图片URL
- 分析树木的种类、生长阶段、健康状况等
- 提供专业的农事指导建议

## 部署方式

### 使用Docker部署

1. 构建Docker镜像：
```bash
docker build -t tree-analyzer .
```

2. 运行容器：
```bash
docker run -p 5000:5000 -e API_KEYS="your-api-key" -e HUNYUAN_API_KEY="your-hunyuan-api-key" tree-analyzer
```

### 使用Docker Compose部署

1. 修改`docker-compose.yml`文件中的环境变量：
   - `API_KEYS`: 设置API访问密钥，多个密钥用逗号分隔
   - `HUNYUAN_API_KEY`: 设置腾讯混元API密钥

2. 启动服务：
```bash
docker-compose up -d
```

## API接口

### 健康检查

```
GET /
```

返回服务基本信息和使用说明。

### 健康检查

```
GET /health
```

返回服务健康状态。

### 图像分析

```
POST /analyze
```

Headers:
- `X-API-Key`: API访问密钥
- `Content-Type`: application/json

Body:
```json
{
  "image_urls": [
    "http://example.com/image1.jpg",
    "http://example.com/image2.jpg"
  ]
}
```

## 环境变量

- `API_KEYS`: API访问密钥，多个密钥用逗号分隔
- `HUNYUAN_API_KEY`: 腾讯混元API密钥
- `PORT`: 服务端口，默认5000

## 使用示例

```bash
curl -X POST http://localhost:5000/analyze \
  -H "X-API-Key: your-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "image_urls": [
      "http://example.com/tree1.jpg",
      "http://example.com/tree2.jpg"
    ]
  }'
```

## 返回结果

服务将返回JSON格式的分析结果，包括：
- 树种识别
- 图像质量诊断
- 当前生长阶段
- 长势诊断
- 叶部状态诊断
- 果实状态诊断
- 营养状况诊断
- 病虫害诊断
- 果叶比与树体评估
- 综合建议