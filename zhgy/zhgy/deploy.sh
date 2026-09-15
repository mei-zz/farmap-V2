#!/bin/bash

# 部署脚本

# 设置变量
APP_NAME="zhgy-app"
CONTAINER_NAME="zhgy-container"
NETWORK_NAME="zhgy-network"

# 停止并删除旧容器
echo "停止并删除旧容器..."
docker stop $CONTAINER_NAME 2>/dev/null || true
docker rm $CONTAINER_NAME 2>/dev/null || true

# 删除旧镜像
echo "删除旧镜像..."
docker rmi $APP_NAME 2>/dev/null || true

# 构建新镜像
echo "构建新镜像..."
docker build -t $APP_NAME .

# 创建网络（如果不存在）
echo "创建Docker网络..."
docker network create $NETWORK_NAME 2>/dev/null || true

# 运行容器
echo "运行容器..."
docker run -d \
  --name $CONTAINER_NAME \
  --network $NETWORK_NAME \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://your-mysql-host:3307/zhgy?useSSL=false&serverTimezone=UTC \
  -e SPRING_DATASOURCE_USERNAME=your-db-username \
  -e SPRING_DATASOURCE_PASSWORD=your-db-password \
  -e GAODE_API_KEY=your-gaode-api-key \
  $APP_NAME

echo "应用部署完成！"