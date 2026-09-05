#!/bin/bash

# 清理旧的构建
echo "清理旧的构建..."
mvn clean

# 打包应用
echo "打包应用..."
mvn package -DskipTests

# 构建Docker镜像
echo "构建Docker镜像..."
docker build -t zhgy-app .

echo "构建完成！"