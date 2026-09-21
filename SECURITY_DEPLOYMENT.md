# SmartDoc 安全部署清单

## 必填秘密

- `JWT_KEYS`：格式 `current:<至少32字节>,previous:<旧密钥>`；`JWT_ACTIVE_KID=current`。轮换时先加入新密钥并切换 active kid，旧密钥保留到旧访问令牌全部过期后再删除。
- `INTERNAL_SERVICE_TOKEN`：独立随机值，不得和 JWT、数据库、MinIO 密码复用。
- 生产覆盖文件默认通过 Compose Secret 挂载密钥。分别创建 `secrets/jwt_keys.txt` 与 `secrets/internal_service_token.txt`，并通过 `JWT_KEYS_SECRET_FILE`、`INTERNAL_SERVICE_TOKEN_SECRET_FILE` 指向它们；这两个文件不得提交 Git。
- 配置 SMTP 后将 `PASSWORD_RESET_MAIL_ENABLED=true`，否则忘记密码接口只返回统一提示且不会泄露令牌。

可用以下 PowerShell 命令分别生成随机值：

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
```

## HTTPS 与端口

1. 将证书放到独立目录，文件名为 `fullchain.pem`、`privkey.pem`。
2. 设置 `SMARTDOC_DOMAIN`、`TLS_CERT_DIR` 和生产域名 `CORS_ALLOWED_ORIGINS`。
3. 启动：`docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`。
4. 防火墙只放行 80/443。MySQL、Redis、RabbitMQ、MinIO、8081–8084 均不映射宿主机；网关也仅在内部网络开放。

## 密码重置邮件

配置企业内部邮件中继 Webhook：`PASSWORD_RESET_DELIVERY_URL`、`PASSWORD_RESET_DELIVERY_TOKEN` 和 `PASSWORD_RESET_FRONTEND_URL`。Webhook 接收 `to`、`subject`、`text` JSON 字段；邮件供应商密钥只保存在中继服务，不进入 SmartDoc 容器。

## 上线验收

- 客户端伪造 `X-User-Id`、`X-Role`、`X-Organization-Id`、`X-Internal-Service-Token` 时均会被网关删除；企业 ID 只能来自业务参数并由服务端校验成员关系。
- 除 `/actuator/health` 外，Actuator 与 Swagger 仅管理员可访问。
- 注销、密码重置、企业管理员强制退出后，旧 Access/Refresh Token 均不可再使用。
- 执行跨租户测试，确认企业 B 用户无法按 ID 读取企业 A 的文档及其评论、版本、分享与 RAG 内容。
