# 部署到腾讯云服务器指南

## 准备工作

1. 确保您已经有一个腾讯云服务器，并且已经安装了以下软件：
   - Docker
   - Docker Compose
   - Nginx

2. 确保您的MySQL数据库已经在3307端口运行，并且已经创建了zhgy数据库

3. 准备好您的高德地图API Key

## 部署步骤

### 1. 传输文件到服务器

将以下文件传输到您的腾讯云服务器（例如放到 `/home/ubuntu/zhgy` 目录下）：

```
- zhgy-0.0.1-SNAPSHOT.jar (在target目录下)
- Dockerfile
- docker-compose-prod.yml
- nginx.conf
```

您可以使用SCP命令传输文件：

```bash
# 在本地执行
scp target/zhgy-0.0.1-SNAPSHOT.jar ubuntu@your-server-ip:/home/ubuntu/zhgy/
scp Dockerfile ubuntu@your-server-ip:/home/ubuntu/zhgy/
scp docker-compose-prod.yml ubuntu@your-server-ip:/home/ubuntu/zhgy/
scp nginx.conf ubuntu@your-server-ip:/home/ubuntu/zhgy/
```

### 2. 在服务器上修改配置

SSH连接到您的腾讯云服务器：

```bash
ssh ubuntu@your-server-ip
```

进入项目目录：

```bash
cd /home/ubuntu/zhgy
```

修改 [docker-compose-prod.yml](file:///E:/zhgy/zhgy/docker-compose-prod.yml) 文件中的配置：

```bash
vim docker-compose-prod.yml
```

需要修改的配置项：
1. `SPRING_DATASOURCE_USERNAME` 和 `SPRING_DATASOURCE_PASSWORD` - 数据库用户名和密码
2. `GAODE_API_KEY` - 您的高德地图API Key

### 3. 构建并启动Docker容器

在项目目录下执行以下命令：

```bash
# 构建Docker镜像
docker-compose -f docker-compose-prod.yml build

# 启动服务
docker-compose -f docker-compose-prod.yml up -d
```

### 4. 配置Nginx反向代理

将 [nginx.conf](file:///E:/zhgy/zhgy/nginx.conf) 文件复制到Nginx配置目录：

```bash
sudo cp nginx.conf /etc/nginx/sites-available/zhgy
sudo ln -s /etc/nginx/sites-available/zhgy /etc/nginx/sites-enabled/
```

修改Nginx配置文件中的域名：

```bash
sudo vim /etc/nginx/sites-available/zhgy
```

将 `your-domain.com` 替换为您自己的域名。

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
docker logs zhgy-app  # 查看应用日志
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
# 在项目目录下查看日志
tail -f logs/application.log
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

- 查看容器日志: `docker logs zhgy-app`
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
   docker-compose -f docker-compose-prod.yml down
   
   # 重新构建镜像
   docker-compose -f docker-compose-prod.yml build
   
   # 启动新容器
   docker-compose -f docker-compose-prod.yml up -d
   ```