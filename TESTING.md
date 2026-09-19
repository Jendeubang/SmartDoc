# SmartDoc 测试指南

项目测试分为后端单元/上下文测试、前端逻辑测试和前端生产构建检查。

## 本地执行

在项目根目录运行后端测试：

```powershell
mvn -B -s .mvn/test-settings.xml test
```

测试设置会把 Maven 依赖缓存到项目本地 `.m2repo`（已加入 `.gitignore`），避免受系统 Maven 仓库权限影响。

运行前端测试和生产构建：

```powershell
Set-Location vue-test-app
npm test
npm run build
```

## 覆盖范围

- `common`：JWT 类型隔离与密钥轮换。
- `user-service`：启动上下文、密码复杂度、注册、账户锁定、管理员强制退出。
- `file-service`：启动上下文、上传校验、路径规范化与目录穿越防护。
- `ai-service`：Agent 规划执行、工具审批、异步任务、AIOps、关键词解析、文档切分等。
- `document-service`：启动上下文、文档权限、企业租户越权隔离。
- `gateway-service`：启动上下文、客户端身份头清理、鉴权白名单、未认证响应、IP/CIDR 白名单。
- `vue-test-app`：异步任务轮询、错误信息归一化、莫兰迪主题保存与回退。

需要真实大模型密钥的 `AllModelsTest` 默认跳过，避免 CI 产生费用；仅在明确配置对应环境变量时执行。

## 持续集成

GitHub Actions 在 push 和 pull request 时自动执行：

1. Maven 后端验证；
2. 前端逻辑测试；
3. Vite 生产构建；
4. Docker Compose 配置校验。
