# 腾讯云 COS 监控画面上传

当前版本不在网页中拦截摄像头厂商的“截图下载”动作，也不提供重复的本地截图上传按钮。FarMap 只保留后端 COS 上传边界，供未来接入厂商 API 或浏览器扩展时复用。

## 本机配置

将以下两个值写入仓库根目录的本地 `.env`，不要提交到 Git，也不要粘贴到聊天中：

```dotenv
TENCENT_COS_ENABLED=true
TENCENT_COS_SECRET_ID=你的SecretId
TENCENT_COS_SECRET_KEY=你的SecretKey
```

项目已配置好本次桶的非敏感参数：

```dotenv
TENCENT_COS_REGION=ap-chongqing
TENCENT_COS_BUCKET=farmap-1317766785
TENCENT_COS_PUBLIC_BASE_URL=
TENCENT_COS_PREFIX=farmap/diagnosis
```

`TENCENT_COS_PUBLIC_BASE_URL` 留空时，后端会返回 2 小时有效的签名读取地址，桶可以保持私有；只有明确配置了公开读策略时才填写桶域名。

## 获取密钥

腾讯云控制台 → 右上角账号 → 访问管理 CAM → API 密钥管理 → 创建/查看 SecretId、SecretKey。SecretKey 如果没有保存，需要新建一对密钥。建议为答辩环境使用一个仅允许访问本桶 `farmap/diagnosis/*` 的子账号密钥。

## 当前限制

普通网页无法监听另一个网站的下载按钮，也无法在下载完成后自动读取本机下载目录。要实现厂商截图按钮“一次点击直接上传”，必须由摄像头厂商提供截图 API，或后续增加浏览器扩展/本地辅助程序。
