# SmartDoc 文档智能处理平台

> 面向个人与中小企业的 AI 文档管理、知识库问答与智能协作平台。

[![Java](https://img.shields.io/badge/Java-17-orange)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-green)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3-brightgreen)](https://vuejs.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-blue)](https://www.docker.com/)
[![License](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)

## 项目简介

SmartDoc 是一个基于 Java 17、Spring Boot 和 Spring Cloud 构建的文档智能处理平台，覆盖文档上传、在线管理、AI 对话、RAG 知识库、生产力工具箱和企业协作等场景。

项目采用前后端分离和微服务架构，AI 能力通过 Spring AI、DeepSeek、DashScope 与自定义 Agent 工具链接入，支持本地 Docker Compose 启动，适合学习、技术验证和项目展示。

## 产品能力

### 文档管理

- 文档上传、解析状态、在线编辑和文档预览
- 文档分类、收藏、回收站、恢复和永久删除
- 图片、PDF、Word、TXT 等常见文档类型管理
- 文档版本、评论、建议模式和协作权限

### SmartDoc AI

- SmartDoc AI 多轮对话和历史会话持久化
- 选择指定文档进行问答
- 基于知识库进行检索和总结
- 文档摘要、纠错、关键词提取、改写和翻译
- Agent 任务规划、工具调用、结果观察和进度推送

### RAG 知识库

- 自动、章节、固定长度和语义分段
- DashScope text-embedding-v4 向量生成
- Redis 元数据持久化
- Java 进程内 HNSW 近似检索
- BM25 与向量检索混合召回
- 权限感知过滤和多用户知识库隔离
- 异步索引、Hash 去重、失败重试和索引状态查询

### 文档生产力工具箱

- 图片 OCR
- PDF 拆分与合并
- Word/PDF 格式转换
- Word 表格导出 Excel
- 图片压缩、裁剪、旋转、转 PDF 和图片合并
- AI 文档改写与多语言翻译
- 录音 ASR 转会议纪要
- 智能练题和批量文档处理

部分工具依赖外部模型或系统组件，具体可用能力以当前配置和页面状态为准。

工具箱任务采用可靠异步处理：MySQL 保存任务状态和重试信息，RabbitMQ 负责调度，Redis 只缓存任务快照与进度，MinIO 保存结果文件。重复消息通过数据库原子状态转换跳过，服务重启后会恢复停滞任务，结果对象按过期时间清理。

文件上传默认限制单文件 100 MB、单批最多 100 个文件、单批总大小 1024 MB，批处理并发上限为 1；服务端会在第一次写入前完成批量边界校验。普通上传通过可重复输入流完成摘要计算、病毒扫描和对象写入，分片合并先写入临时文件再流式上传，并在成功或失败后清理临时分片。

RAG 检索会先从 document-service 获取当前用户可访问的文档 ID，并将该集合同时应用于 HNSW、Redis 向量检索和 BM25 召回；无权限候选不会进入混合融合、Rerank 或回答上下文构建。企业请求还会使用服务端校验后的 TenantContext 约束组织范围。

### 企业空间与协作

- 企业成员、部门和角色权限管理
- 个人工作台文档同步到企业空间
- 文档共享、评论、建议和版本差异
- 段落锁，减少多人同时编辑同一段内容造成的覆盖
- 安全分享链接和过期访问控制

## 系统架构

~~~mermaid
flowchart LR
    UI[Vue 3 / Vite] --> GW[Gateway :8080]
    GW --> USER[User Service :8081]
    GW --> FILE[File Service :8082]
    GW --> AI[AI Service :8083]
    GW --> DOC[Document Service :8084]
    USER --> MYSQL[(MySQL)]
    FILE --> MINIO[(MinIO)]
    DOC --> MYSQL
    DOC --> MINIO
    USER --> REDIS[(Redis)]
    AI --> REDIS
    FILE --> MQ[(RabbitMQ)]
    DOC --> MQ
    AI --> MQ
    AI --> MODEL[DeepSeek / DashScope]
~~~

详细架构见 [架构说明](docs/ARCHITECTURE.md)，接口列表见 [API 概览](docs/API.md)。

## 技术栈

| 模块 | 技术 |
| --- | --- |
| 前端 | Vue 3.5.25、Vite 4.5.3、Element Plus 2.14.0、Axios 1.13.5、Node.js 20（容器构建） |
| 后端 | Java 17、Spring Boot 3.2.0、Spring Cloud 2023.0.3、Spring Cloud Alibaba 2023.0.1.0、Spring AI 1.0.0-M4 |
| 数据访问 | MyBatis-Plus 3.5.5、MySQL 8.0、MyBatis、JJWT 0.11.5 |
| 中间件 | Redis Stack Server 7.2.0-v20、RabbitMQ 3.8-management、MinIO RELEASE.2024-06-13T22-53-53Z、ClamAV 1.4 |
| AI 能力 | DeepSeek、DashScope、Embedding、Rerank、Tesseract OCR |
| 检索 | HNSW、BM25、混合检索、权限过滤 |
| 部署 | Docker Compose、Docker Swarm、Nginx 1.27、Prometheus |

### 版本与兼容性说明

一键部署使用仓库中的 Dockerfile 和 Compose 文件，不需要在本机安装 JDK、Maven 或 Node.js。构建镜像中的固定版本如下：

| 组件 | 版本/配置 | 来源 |
| --- | --- | --- |
| Java 运行时 | Eclipse Temurin 17 JRE | `deploy/docker/backend.Dockerfile` |
| Maven 构建环境 | Maven 3.9.9 + Eclipse Temurin 17 | `deploy/docker/backend.Dockerfile` |
| 前端构建环境 | Node.js 20 Alpine | `deploy/nginx/Dockerfile` |
| 前端运行环境 | Nginx 1.27 Alpine | `deploy/nginx/Dockerfile` |
| MySQL | 8.0 | `docker-compose.yml` |
| Redis | Redis Stack Server 7.2.0-v20（RediSearch/HNSW） | `docker-compose.yml` |
| MinIO | RELEASE.2024-06-13T22-53-53Z | `docker-compose.yml` |
| RabbitMQ | 3.8-management | `docker-compose.yml` |
| ClamAV | 1.4 | `docker-compose.yml` |

Spring Boot、Spring Cloud、Spring Cloud Alibaba、Spring AI 和 Java 依赖的版本以根目录 `pom.xml` 为准；前端依赖版本以 `vue-test-app/package.json` 和 `package-lock.json` 为准。不要随意升级其中一组版本，否则可能出现 Spring Cloud 兼容性、Embedding 维度或 Redis 向量索引不一致问题。

## 快速启动

### Docker Compose 一键部署（推荐）

只需要安装 Docker Desktop（包含 Docker Compose），不需要在宿主机安装 JDK、Maven 或 Node.js。首次启动会在 Docker 内构建 Java 服务和 Vue 前端，可能需要数分钟；建议至少 4 核 8 GB 内存，4 GB 机器可能在首次构建或启动 ClamAV 时内存不足。

#### Windows 从 GitHub 启动

Windows PowerShell：

~~~powershell
git clone https://github.com/Jendeubang/SmartDoc.git
Set-Location SmartDoc
.\deploy\quickstart.ps1
~~~

如果 PowerShell 禁止执行脚本，可以只对当前命令放开策略：

~~~powershell
powershell -ExecutionPolicy Bypass -File .\deploy\quickstart.ps1
~~~

#### Linux 从 GitHub 启动

Linux：

~~~bash
git clone https://github.com/Jendeubang/SmartDoc.git
cd SmartDoc
chmod +x deploy/quickstart.sh
./deploy/quickstart.sh
~~~

启动脚本会自动完成以下步骤：

1. 从 `.env.example` 创建本地 `.env`；
2. 生成数据库密码、JWT 密钥、内部服务令牌和 AI 凭证加密主密钥；
3. 使用项目指定版本构建后端与前端镜像；
4. 启动 MySQL、Redis Stack、RabbitMQ、MinIO、ClamAV、微服务和前端；
5. 输出管理员账号、随机初始密码和访问地址。

脚本会自动创建 `.env`、生成本地随机凭证、构建镜像、启动 MySQL、Redis、RabbitMQ、MinIO、ClamAV、微服务和前端。启动完成后访问：

~~~text
http://localhost:8088
~~~

管理员用户名默认为 `admin`，初始密码会由脚本生成并打印。模型 API Key 为空时，登录、文件管理等基础功能仍可使用；需要 AI、Embedding、ASR 等功能时，在 `.env` 中填写 `OPENAI_API_KEY` 或 `DASHSCOPE_API_KEY` 后执行：

~~~powershell
docker compose --env-file .env -f docker-compose.yml -f docker-compose.quickstart.yml up -d --build
~~~

如果本机 `8088` 端口已被占用，修改 `.env` 中的 `SMARTDOC_WEB_PORT`，例如改为 `8090`，然后重新执行启动脚本。浏览器访问对应的新端口。

常用命令：

~~~bash
docker compose --env-file .env -f docker-compose.yml -f docker-compose.quickstart.yml ps
docker compose --env-file .env -f docker-compose.yml -f docker-compose.quickstart.yml logs -f
docker compose --env-file .env -f docker-compose.yml -f docker-compose.quickstart.yml down
~~~

快速体验模式默认只暴露前端 `8088` 端口，数据库、Redis、RabbitMQ、MinIO 和微服务只在 Compose 内部网络可访问。它适合本地开发和项目展示，不等同于公网生产部署。

### 本地开发模式

本地开发需要 JDK 17、Node.js 18+ 和 Docker Compose。先执行：

~~~powershell
Copy-Item .env.example .env
./mvnw.cmd -DskipTests package
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
Set-Location ./vue-test-app
npm install
npm run dev
~~~

开发环境前端访问 `http://localhost:5173`，API 网关访问 `http://localhost:8080`。

推荐体验流程：注册并登录 → 上传文档 → 打开文档 → 进入 SmartDoc AI → 选择文档 → 开启“基于文档回答” → 提问。

## 常用地址

| 服务 | 地址 |
| --- | --- |
| 一键部署前端 | http://localhost:8088 |
| 开发前端 | http://localhost:5173 |
| API 网关（开发模式） | http://localhost:8080 |
| MinIO 控制台（开发模式） | http://localhost:9001 |
| RabbitMQ 控制台（开发模式） | http://localhost:15672 |
| 健康检查 | http://localhost:8080/actuator/health |

开发环境使用 `docker-compose.dev.yml`，基础设施和网关端口只绑定到 `127.0.0.1`，各微服务仅在 Compose 网络内可访问。生产环境必须使用 `docker-compose.prod.yml`，由 Nginx 仅发布 80/443；生产合成配置不得发布数据库、Redis、RabbitMQ、MinIO 或任何微服务端口。

生产启动与端口校验：

~~~powershell
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
./deploy/verify-production-ports.ps1
~~~

## 项目结构

~~~text
SmartDoc/
├─ common/              公共返回、JWT、Feign 与基础配置
├─ gateway-service/     API 网关、鉴权与限流
├─ user-service/        用户、登录和会话管理
├─ file-service/        文件上传、下载和 MinIO 元数据
├─ document-service/    文档、版本、协作和工具箱任务
├─ ai-service/          Agent、RAG、模型调用和 AI 任务
├─ vue-test-app/        Vue 前端
├─ init-db/             MySQL 初始化脚本和迁移
├─ deploy/              部署脚本和 Nginx 配置
├─ docs/                架构、API 和安全测试文档
├─ evaluation/          RAG 评测数据、脚本和 JMeter 配置
└─ docker-compose.yml   本地完整部署配置
~~~

## 测试与评测

### 基础测试

~~~powershell
./mvnw.cmd test
Set-Location ./vue-test-app
npm test
npm run build
Set-Location ..
docker compose -f docker-compose.yml -f docker-compose.dev.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.prod.yml config --quiet
~~~

### RAG 效果评测

评测脚本位于 evaluation/scripts/，数据集模板位于 evaluation/datasets/：

~~~powershell
python evaluation/scripts/run_rag_eval.py --dataset evaluation/datasets/rag_qa_v1.jsonl --base-url http://localhost:8080 --token-env SMARTDOC_TOKEN --knowledge-base-id default --output-dir evaluation/results/rag-eval-v1
~~~

输出包括 Recall@1、Recall@3、Recall@5、MRR@5 和逐题明细。

### RAG 性能压测

JMeter 测试计划位于 evaluation/jmeter/smartdoc-rag-search.jmx，PowerShell 启动脚本位于 evaluation/jmeter/run-rag-performance.ps1。脚本会输出 JMeter 原始 JTL 结果和 HTML 报告。

~~~powershell
./evaluation/jmeter/run-rag-performance.ps1 -JMeterBin '你的JMeter安装目录/bin/jmeter.bat' -Token $env:SMARTDOC_TOKEN -Threads 5 -RampUpSeconds 30 -Loops 200 -ThinkTimeMs 1500
~~~

评测数据应使用脱敏或公开数据，不要将真实客户文档、Token 或个人信息提交到仓库。

### 工具箱任务迁移

首次启用可靠任务持久化时，执行新增迁移 `init-db/migrations/V20260920_03__toolbox_job_persistence.sql`。使用项目迁移脚本时：

~~~powershell
$env:MYSQL_ROOT_PASSWORD="你的MySQL密码"
./deploy/scripts/migrate.ps1 -ComposeFile docker-compose.yml
~~~

工具箱恢复默认每 30 秒扫描一次：PROCESSING 任务租约为 10 分钟，最多每轮恢复 50 个任务；结果默认保留 7 天。可通过 `toolbox.recovery.fixed-delay-ms` 和 `toolbox.cleanup.fixed-delay-ms` 调整扫描周期。Redis 不保存结果文件二进制，Redis 暂时不可用时任务状态仍以 MySQL 为准，结果仍从 MinIO 读取。

## 安全设计

- JWT 密钥支持环境变量或 Secret 文件注入和密钥轮换
- Refresh Token 存入 Redis，支持轮换和注销失效
- 管理员可强制成员退出登录
- 网关移除客户端伪造的身份请求头，再注入可信身份
- 微服务之间使用内部服务令牌
- 文件扩展名、文件头和上传大小校验
- 默认强制启用 ClamAV 文件扫描，扫描服务不可用时拒绝上传
- 文档、企业空间和知识库的权限过滤
- Redis、消息队列和文件存储使用独立服务配置

## 当前状态

SmartDoc 当前定位为可运行的学习、展示和技术验证项目。基础文档管理、AI 对话、RAG 检索、工具箱和企业协作能力已经接入；部分高级工具和第三方 AI 能力需要额外配置模型服务或系统依赖。

如果用于生产环境，还需要根据实际部署补充域名、HTTPS、密钥托管、备份恢复、监控告警、资源限制和灾备方案。

## 相关文档

- [架构说明](docs/ARCHITECTURE.md)
- [API 概览](docs/API.md)
- [安全部署说明](SECURITY_DEPLOYMENT.md)
- [P0 运维清单](P0_OPERATIONS.md)
- [测试说明](TESTING.md)
- [RAG 评测说明](evaluation/README.md)

## 许可证

本项目采用 MIT License，详见 [LICENSE](LICENSE)。
