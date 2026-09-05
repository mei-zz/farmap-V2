# 简化部署方案

## 准备工作

1. 确保您已经有一个腾讯云服务器，并且已经安装了以下软件：
   - Docker
   - MySQL (已经在3307端口运行)
   - Nginx (已经配置好)

2. 确保您的MySQL数据库已经在3307端口运行，并且已经创建了zhgy数据库

3. 准备好您的高德地图API Key

## 部署步骤

### 1. 传输文件到服务器

将以下文件传输到您的腾讯云服务器（例如放到 `/home/ubuntu/zhgy` 目录下）：

```
- zhgy-0.0.1-SNAPSHOT.jar (在target目录下)
- Dockerfile
```

您可以使用SCP命令传输文件：

```bash
# 在本地执行
scp target/zhgy-0.0.1-SNAPSHOT.jar ubuntu@your-server-ip:/home/ubuntu/zhgy/
scp Dockerfile ubuntu@your-server-ip:/home/ubuntu/zhgy/
```

### 2. 在服务器上构建Docker镜像

SSH连接到您的腾讯云服务器：

```bash
ssh ubuntu@your-server-ip
```

进入项目目录：

```bash
cd /home/ubuntu/zhgy
```

构建Docker镜像：

```bash
docker build -t zhgy-app .
```

### 3. 运行Docker容器

运行容器并配置环境变量（两种方式）：

**方式一：单行命令（推荐）**
```bash
docker run -d --name zhgy-container -p 8080:8080 -v /home/ubuntu/zhgy/logs:/app/logs -e SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3307/zhgy?useSSL=false&serverTimezone=UTC -e SPRING_DATASOURCE_USERNAME=mei -e SPRING_DATASOURCE_PASSWORD=mei123456 -e GAODE_API_KEY=your_gaode_api_key_here zhgy-app
```

**方式二：多行命令**
```bash
docker run -d \
  --name zhgy-container \
  -p 8080:8080 \
  -v /home/ubuntu/zhgy/logs:/app/logs \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3307/zhgy?useSSL=false&serverTimezone=UTC \
  -e SPRING_DATASOURCE_USERNAME=mei \
  -e SPRING_DATASOURCE_PASSWORD=mei123456 \
  -e GAODE_API_KEY=your_gaode_api_key_here \
  zhgy-app
```

注意：如果使用方式二，请确保所有行都在同一终端命令中执行，不要分行执行。

### 4. 配置现有的Nginx

在您现有的Nginx配置中添加一个新的server块或者修改现有的配置，添加以下内容：

```nginx
server {
    listen 80;
    server_name your-domain.com;  # 替换为你的域名

    # 代理API请求
    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

重新加载Nginx配置：

```bash
sudo nginx -t  # 测试配置文件
sudo systemctl reload nginx  # 重新加载Nginx
```

### 5. 配置域名解析

在您的域名提供商处添加A记录，将域名指向您的腾讯云服务器IP地址。

### 6. （可选）配置SSL证书

如果您希望使用HTTPS，可以使用Let's Encrypt获取免费SSL证书：

```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
```

## 验证部署

### 1. 检查容器状态

```bash
docker ps  # 查看运行中的容器
docker logs zhgy-container  # 查看应用日志
```

### 2. 测试API接口

```bash
# 测试用户登录接口
curl -X POST http://your-domain.com/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"guest","password":"123456"}'

# 测试天气接口
curl http://your-domain.com/weather/intro?farmType=plum
```

## 常见问题排查

### 1. 数据库连接失败

- 检查MySQL是否在3307端口运行
- 检查数据库用户名和密码是否正确
- 确保Docker容器可以访问宿主机的MySQL服务（使用host.docker.internal）

### 2. API无法访问

- 检查Nginx配置是否正确
- 确认容器是否正常运行
- 检查服务器防火墙设置

### 3. 容器启动失败

- 查看容器日志: `docker logs zhgy-container`
- 检查环境变量配置
- 确认端口是否被占用

## 更新部署

当您需要更新应用时：

1. 重新打包应用：
   ```bash
   # 在本地
   mvn clean package -DskipTests
   ```

2. 传输新的jar文件到服务器：
   ```bash
   scp target/zhgy-0.0.1-SNAPSHOT.jar ubuntu@your-server-ip:/home/ubuntu/zhgy/
   ```

3. 在服务器上更新容器：
   ```bash
   # SSH连接到服务器
   cd /home/ubuntu/zhgy
   
   # 停止并删除旧容器
   docker stop zhgy-container
   docker rm zhgy-container
   
   # 重新构建镜像
   docker build -t zhgy-app .
   
   # 启动新容器（使用单行命令）
   docker run -d --name zhgy-container -p 8080:8080 -v /home/ubuntu/zhgy/logs:/app/logs -e SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3307/zhgy?useSSL=false&serverTimezone=UTC -e SPRING_DATASOURCE_USERNAME=mei -e SPRING_DATASOURCE_PASSWORD=mei123456 -e GAODE_API_KEY=your_gaode_api_key_here zhgy-app
   ```