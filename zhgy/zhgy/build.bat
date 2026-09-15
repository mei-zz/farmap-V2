@echo off
echo 清理旧的构建...
call mvn clean

echo 打包应用...
call mvn package -DskipTests

echo 构建Docker镜像...
docker build -t zhgy-app .

echo 构建完成！