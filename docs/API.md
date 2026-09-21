# SmartDoc API 概览

所有业务请求建议通过 `http://localhost:8080` 访问。除登录、注册和健康检查外，请携带：

```http
Authorization: Bearer <JWT>
```

## 用户

- `POST /api/users/login`：登录
- `POST /api/users/register`：注册
- `POST /api/users/refresh`：刷新令牌

## 文件

- `POST /api/files/upload`：单文件上传
- `POST /api/files/upload-multiple`：多文件上传
- `GET /api/files/download/{fileId}`：下载文件
- `GET /api/files/preview/{fileId}`：预览文件
- `DELETE /api/files/{fileId}`：删除文件

上传接口默认限制 100MB，仅接受平台白名单类型，并校验常见文件签名。

## 文档

- `POST /api/documents`：创建文档
- `GET /api/documents/{id}`：读取文档
- `PUT /api/documents/{id}`：更新文档
- `DELETE /api/documents/{id}`：移入回收站
- `GET /api/documents/trash`：回收站列表
- `POST /api/documents/{id}/restore`：恢复文档
- `DELETE /api/documents/{id}/purge`：永久删除
- `POST /api/documents/{id}/collaborators/{userId}`：授权协作者，参数 `role`、`expiresHours`
- `GET /api/documents/toolbox/jobs`：任务中心
- `POST /api/documents/toolbox/jobs/{jobId}/retry`：重试任务
- `GET /api/documents/toolbox/jobs/{jobId}/result`：下载结果

## AI 与 Agent

- `POST /api/ai/agent/execute`：执行 Agent 任务
- `GET /api/ai/agent/tools`：工具清单
- `POST /api/ai/agent/knowledge/index/segment`：异步建立索引
- `GET /api/ai/agent/knowledge/jobs`：索引任务列表
- `POST /api/ai/agent/knowledge/jobs/{jobId}/retry`：重试索引
- `GET /api/ai/agent/knowledge/search`：知识库检索
- `POST /api/ai/meeting-minutes/audio`：录音转会议纪要

准确请求模型和响应字段以各服务 Swagger 页面为准。

## 运维

- `GET /actuator/health`：健康状态
- `GET /actuator/info`：应用信息
- `GET /actuator/prometheus`：Prometheus 指标