# 应用部署指南

## 目录
1. [环境准备](#环境准备)
2. [本地构建](#本地构建)
3. [服务器部署](#服务器部署)
4. [Nginx配置](#nginx配置)
5. [域名配置](#域名配置)
6. [验证部署](#验证部署)

## 环境准备

### 本地环境
- Java 8 或更高版本
- Maven 3.6 或更高版本
- Docker 20.10 或更高版本
- Docker Compose 1.29 或更高版本

### 服务器环境
- Ubuntu 18.04 或更高版本（推荐）
- Docker 和 Docker Compose 已安装
- Nginx 已安装
- MySQL 8.0 已安装并运行在 3307 端口

## 本地构建

### 1. 克隆代码
```bash
git clone <your-repo-url>
cd zhgy
```

### 2. 构建应用
```bash
# Linux/Mac
chmod +x build.sh
./build.sh

# Windows
build.bat
```

### 3. 测试Docker镜像
```bash
docker run -p 8080:8080 zhgy-app
```

## 服务器部署

### 1. 传输文件到服务器
```bash
# 使用scp传输文件到服务器
scp target/zhgy-0.0.1-SNAPSHOT.jar user@your-server-ip:/path/to/deploy/
scp Dockerfile user@your-server-ip:/path/to/deploy/
scp docker-compose.yml user@your-server-ip:/path/to/deploy/
```

### 2. 在服务器上部署

#### 方法一：使用Docker Compose（推荐）
```bash
# SSH连接到服务器
ssh user@your-server-ip

# 进入部署目录
cd /path/to/deploy

# 启动服务
docker-compose up -d
```

#### 方法二：手动部署
```bash
# SSH连接到服务器
ssh user@your-server-ip

# 进入部署目录
cd /path/to/deploy

# 构建Docker镜像
docker build -t zhgy-app .

# 运行容器
docker run -d \
  --name zhgy-container \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/zhgy?useSSL=false&serverTimezone=UTC \
  -e SPRING_DATASOURCE_USERNAME=mei \
  -e SPRING_DATASOURCE_PASSWORD=mei123456 \
  -e GAODE_API_KEY=your-gaode-api-key \
  zhgy-app
```

### 3. 环境变量配置
在运行容器时，需要配置以下环境变量：
- `SPRING_DATASOURCE_URL`: 数据库连接URL
- `SPRING_DATASOURCE_USERNAME`: 数据库用户名 (mei)
- `SPRING_DATASOURCE_PASSWORD`: 数据库密码 (mei123456)
- `GAODE_API_KEY`: 高德地图API密钥

## Nginx配置

### 1. 复制Nginx配置文件
将 [nginx.conf](file:///E:/zhgy/zhgy/nginx.conf) 文件复制到服务器的Nginx配置目录：
```bash
sudo cp nginx.conf /etc/nginx/sites-available/zhgy
sudo ln -s /etc/nginx/sites-available/zhgy /etc/nginx/sites-enabled/
```

### 2. 修改配置文件
编辑 `/etc/nginx/sites-available/zhgy` 文件，将 `your-domain.com` 替换为你的实际域名。

### 3. 重新加载Nginx
```bash
sudo nginx -t  # 测试配置文件
sudo systemctl reload nginx  # 重新加载Nginx
```

## 域名配置

### 1. DNS设置
在你的域名提供商处添加A记录，将域名指向你的服务器IP地址。

### 2. SSL证书（可选但推荐）
使用Let's Encrypt获取免费SSL证书：
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

### 3. 查看应用日志
```bash
docker logs -f zhgy-container
```

## 故障排除

### 常见问题

1. **数据库连接失败**
   - 检查数据库是否运行正常
   - 确认数据库连接参数是否正确
   - 检查防火墙设置

2. **API无法访问**
   - 检查Nginx配置
   - 确认容器是否正常运行
   - 检查服务器防火墙设置

3. **容器启动失败**
   - 查看容器日志: `docker logs container-name`
   - 检查环境变量配置
   - 确认端口是否被占用

### 日志查看
```bash
# 查看应用日志
docker logs zhgy-container

# 实时查看日志
docker logs -f zhgy-container

# 查看最近的日志
docker logs --tail 100 zhgy-container
```

## 更新部署

### 1. 构建新版本
```bash
# 本地构建新版本
./build.sh
```

### 2. 传输新文件
```bash
scp target/zhgy-0.0.1-SNAPSHOT.jar user@your-server-ip:/path/to/deploy/
```

### 3. 在服务器上更新
```bash
# SSH连接到服务器
ssh user@your-server-ip

# 停止并删除旧容器
docker stop zhgy-container
docker rm zhgy-container

# 重新构建镜像
docker build -t zhgy-app .

# 启动新容器
docker run -d \
  --name zhgy-container \
  -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/zhgy?useSSL=false&serverTimezone=UTC \
  -e SPRING_DATASOURCE_USERNAME=mei \
  -e SPRING_DATASOURCE_PASSWORD=mei123456 \
  -e GAODE_API_KEY=your-gaode-api-key \
  zhgy-app
```