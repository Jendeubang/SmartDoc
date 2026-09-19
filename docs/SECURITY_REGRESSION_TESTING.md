# SmartDoc 认证安全回归测试

## 覆盖范围

| 安全边界 | 回归内容 |
| --- | --- |
| JWT 密钥轮换 | 新令牌使用 active kid；旧密钥在轮换窗口内可验签；移出密钥环后旧令牌失效；篡改签名拒绝 |
| Refresh Token | Redis 只保存哈希；访问/刷新令牌类型隔离；轮换后旧 Refresh Token 重放失败；Redis 原子 CAS 防止并发重复刷新 |
| 注销与强制下线 | 注销删除会话和 Refresh Token 哈希；管理员强制下线递增用户版本并清理全部会话；旧 Access Token 被网关拒绝 |
| 网关身份边界 | 客户端伪造的 `X-User-Id`、`X-Role` 等头被清除；下游身份只来自 JWT；已撤销会话不能转发 |
| 内部服务令牌 | 内部接口缺失、伪造、错误令牌均为 401；正确令牌通过；Docker Secret 文件可读取；未配置令牌时拒绝内部调用 |
| 服务端身份 | 用户服务个人信息和强制下线接口从 Spring Security Authentication 取操作人，不信任客户端 `X-User-Id` |

## 执行命令

在项目根目录 `F:\DocAI\DocAI-main` 的 PowerShell 执行：

```powershell
mvn -pl common,user-service,gateway-service -am test
```

如果只改了某一层，可以单独执行：

```powershell
mvn -pl common -am test
mvn -pl user-service -am test
mvn -pl gateway-service -am test
```

## 当前测试边界

上述回归测试不依赖本机 Redis、MySQL 或 Docker，适合提交前快速执行；Redis Lua 原子轮换逻辑还应在部署前用真实 Redis 做一次集成冒烟测试。测试通过不代表公网部署已经安全，还需确认生产环境未暴露微服务、Redis、MySQL、RabbitMQ 和 MinIO 端口，并使用 `docker-compose.prod.yml` 的 Secret 配置。
