# DocAI 复现方案

这份文档给两种方式：

- 方案 A：不用 Docker Desktop，本机进程启动，推荐 Windows 复现使用。
- 方案 B：Docker Swarm 单机启动，适合 Linux/WSL2/服务器。

当前优先按方案 A 走。

## 方案 A：不用 Docker Desktop

### 1. 打开哪些窗口

需要多个终端窗口。

窗口 1：后端构建和启动服务。

```powershell
cd F:\DocAI\DocAI-main
```

窗口 2：前端。

```powershell
cd F:\DocAI\DocAI-main\vue-test-app
```

另外 MySQL、Redis、RabbitMQ、MinIO 可以用各自的服务窗口或 Windows 服务启动。

### 2. 安装哪些软件

必须安装：

- JDK 17，项目推荐版本。
- Maven 3.8+。
- Node.js 18+。
- MySQL 8.0。
- Redis 7.x。
- RabbitMQ 3.8+。
- MinIO Server。

可选：

- Nacos。方案 A 默认禁用 Nacos，不需要启动。

检查命令：

```powershell
java -version
mvn -v
node -v
npm -v
mysql --version
redis-server --version
```

如果你当前只有 JDK 21，通常也能编译，但更稳的是安装 JDK 17 并让 `JAVA_HOME` 指向 JDK 17。

### 3. 准备环境变量文件

在项目根目录执行：

```powershell
copy .\reproduce\.env.local.example .\reproduce\.env.local
notepad .\reproduce\.env.local
```

在 `F:\DocAI\DocAI-main\reproduce\.env.local` 里填写下面这些值：

```env
MYSQL_DATABASE=doc_ai
MYSQL_LOCAL_HOST=localhost
MYSQL_LOCAL_PORT=3306
MYSQL_LOCAL_USERNAME=root
MYSQL_LOCAL_PASSWORD=123456

MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_USERNAME=root
MYSQL_PASSWORD=123456
MYSQL_ROOT_PASSWORD=123456

REDIS_LOCAL_HOST=localhost
REDIS_LOCAL_PASSWORD=123456
REDIS_HOST=localhost
REDIS_PASSWORD=123456

RABBITMQ_HOST=localhost
RABBITMQ_USERNAME=guest
RABBITMQ_PASSWORD=guest
RABBITMQ_DEFAULT_USER=guest
RABBITMQ_DEFAULT_PASS=guest

MINIO_LOCAL_ENDPOINT=http://localhost:9000
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=minioadmin

SERVER_ADDRESS=0.0.0.0

SPRING_CLOUD_NACOS_DISCOVERY_ENABLED=false
SPRING_CLOUD_NACOS_CONFIG_ENABLED=false
SPRING_CLOUD_NACOS_REGISTRY_ENABLED=false

GATEWAY_ROUTE_USER_URI=http://localhost:8081
GATEWAY_ROUTE_FILE_URI=http://localhost:8082
GATEWAY_ROUTE_AI_URI=http://localhost:8083
GATEWAY_ROUTE_AI_WS_URI=ws://localhost:8083
GATEWAY_ROUTE_DOCUMENT_URI=http://localhost:8084

VITE_API_TARGET=http://localhost:8080
VITE_AI_TARGET=http://localhost:8083
```

AI 接口需要额外填写：

```env
DASHSCOPE_API_KEY=你的阿里百炼或通义千问Key
OPENAI_API_KEY=你的兼容OpenAI接口Key
OPENAI_BASE_URL=https://coding.dashscope.aliyuncs.com/v1
```

只测试注册、登录、文件、文档时，AI Key 可以先空着。

### 4. 加载环境变量

每次打开新的 PowerShell 窗口，都要在项目根目录执行一次：

```powershell
Get-Content .\reproduce\.env.local | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
```

如果你用 Git Bash / WSL2：

```bash
set -a
source reproduce/.env.local
set +a
```

### 5. 启动 MySQL

确保 MySQL 8.0 正在运行，端口是 `3306`。

登录 MySQL：

```powershell
mysql -uroot -p123456
```

创建业务库：

```sql
CREATE DATABASE IF NOT EXISTS doc_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

退出 MySQL：

```sql
exit;
```

导入业务表：

```powershell
mysql -uroot -p123456 < .\reproduce\init-doc-ai.sql
```

说明：不用 Docker 的方案默认不启动 Nacos，所以不需要导入 `mysql-schema.sql`。

### 6. 启动 Redis

如果你本机 Redis 没设置密码，建议先改成和 `.env.local` 一致：

```text
requirepass 123456
```

启动 Redis 后确认：

```powershell
redis-cli -a 123456 ping
```

正常返回：

```text
PONG
```

如果你不想设置 Redis 密码，把 `.env.local` 里的这些值改空：

```env
REDIS_LOCAL_PASSWORD=
REDIS_PASSWORD=
```

### 7. 启动 RabbitMQ

确保 RabbitMQ 运行在：

```text
localhost:5672
```

管理后台：

```text
http://localhost:15672
```

默认账号：

```text
guest / guest
```

如果没有 RabbitMQ，至少 `user-service` 和 `gateway-service` 可以先跑；`file-service`、`ai-service`、`document-service` 可能会因为消息队列连接失败或功能不可用。

### 8. 启动 MinIO

下载 MinIO Server 后，在一个单独窗口启动：

```powershell
mkdir F:\DocAI\minio-data
.\minio.exe server F:\DocAI\minio-data --console-address ":9001"
```

如果 `minio.exe` 不在当前目录，用它的完整路径，例如：

```powershell
C:\Tools\minio.exe server F:\DocAI\minio-data --console-address ":9001"
```

访问：

```text
http://localhost:9001
```

账号密码来自 `.env.local`：

```text
minioadmin / minioadmin
```

进入 MinIO 后创建两个 bucket：

```text
doc-ai
document
```

### 9. 构建后端

在窗口 1，项目根目录执行：

```powershell
mvn -gs .\reproduce\maven-settings-local.xml clean package -DskipTests
```

这里必须带 `-gs .\reproduce\maven-settings-local.xml`，因为你当前 Maven 全局配置把仓库写到了 `F:\Maven\apache-maven-3.9.4\mvn_repo`，可能没有写权限。

### 10. 启动后端服务

建议按顺序启动。每个服务开一个 PowerShell 窗口，每个窗口都先执行第 4 步的"加载环境变量"。

窗口 1：用户服务。

```powershell
cd F:\DocAI\DocAI-main
mvn -gs .\reproduce\maven-settings-local.xml -pl user-service -am spring-boot:run
```

窗口 2：文件服务。

```powershell
cd F:\DocAI\DocAI-main
mvn -gs .\reproduce\maven-settings-local.xml -pl file-service -am spring-boot:run
```

窗口 3：文档服务。

```powershell
cd F:\DocAI\DocAI-main
mvn -gs .\reproduce\maven-settings-local.xml -pl document-service -am spring-boot:run -Dspring-boot.run.profiles=local
```

窗口 4：AI 服务。

```powershell
cd F:\DocAI\DocAI-main
mvn -gs .\reproduce\maven-settings-local.xml -pl ai-service -am spring-boot:run -Dspring-boot.run.profiles=local
```

窗口 5：网关服务。

```powershell
cd F:\DocAI\DocAI-main
mvn -gs .\reproduce\maven-settings-local.xml -pl gateway-service -am spring-boot:run
```

端口对应：

- gateway：8080
- user：8081
- file：8082
- ai：8083
- document：8084

### 11. 启动前端

打开前端窗口：

```powershell
cd F:\DocAI\DocAI-main\vue-test-app
npm install
npm run dev
```

浏览器访问：

```text
http://localhost:5173
```

前端代理配置在：

```text
F:\DocAI\DocAI-main\vue-test-app\vite.config.js
```

关键内容：

```js
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'
const aiTarget = process.env.VITE_AI_TARGET || 'http://localhost:8083'
```

### 12. 验证接口

注册用户：

```powershell
curl.exe -X POST http://localhost:8080/api/users/register `
  -H "Content-Type: application/json" `
  -d "{\"username\":\"testuser\",\"password\":\"123456\",\"email\":\"test@example.com\",\"phone\":\"13800138000\"}"
```

登录：

```powershell
curl.exe -X POST http://localhost:8080/api/users/login `
  -H "Content-Type: application/json" `
  -d "{\"username\":\"testuser\",\"password\":\"123456\"}"
```

登录成功会返回 `accessToken`。

Swagger 地址：

- User Swagger：http://localhost:8081/swagger-ui.html
- File Swagger：http://localhost:8082/swagger-ui.html
- AI Swagger：http://localhost:8083/swagger-ui.html
- Document Swagger：http://localhost:8084/swagger-ui.html

### 13. 不用 Docker 时的常见问题

#### 服务启动时还在连 nacos:8848

确认你已经在当前窗口加载了环境变量：

```powershell
Get-ChildItem Env:SPRING_CLOUD_NACOS_DISCOVERY_ENABLED
```

应该看到：

```text
false
```

#### 网关 503

确认网关路由变量已经加载：

```powershell
Get-ChildItem Env:GATEWAY_ROUTE_USER_URI
```

应该是：

```text
http://localhost:8081
```

并确认 `user-service` 已经启动成功。

#### Redis 连接失败

如果 Redis 没密码，把 `.env.local` 里的密码清空，然后重新加载环境变量：

```env
REDIS_LOCAL_PASSWORD=
REDIS_PASSWORD=
```

#### RabbitMQ 连接失败

先只启动：

```text
user-service
gateway-service
vue-test-app
```

这样可以先验证注册和登录。文件、文档、AI 功能再等 RabbitMQ 和 MinIO 配好后启动。

#### Maven 报 AccessDeniedException

必须使用：

```powershell
mvn -gs .\reproduce\maven-settings-local.xml clean package -DskipTests
```

## 方案 B：Docker Swarm 单机启动

如果后续你愿意用 Docker 或换到 Linux 服务器，可以用这一套。

### 1. 加载环境变量

```powershell
Get-Content .\reproduce\.env.local | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
  $name, $value = $_ -split '=', 2
  [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
```

### 2. 构建后端

```powershell
mvn -gs .\reproduce\maven-settings-local.xml clean package -DskipTests
```

### 3. 构建镜像

```powershell
docker build -t docai/user-service:1.0.0 user-service/
docker build -t docai/file-service:1.0.0 file-service/
docker build -t docai/gateway-service:1.0.0 gateway-service/
docker build -t docai/ai-service:1.0.0 ai-service/
docker build -t docai/document-service:1.0.0 document-service/
```

### 4. 初始化 Swarm

```powershell
docker swarm init
docker network create --driver overlay --attachable docai-network
```

如果提示已经存在，跳过即可。

### 5. 部署基础设施

```powershell
docker stack deploy -c reproduce/docker-stack-local-infra.yml docai-infra
docker service ls
```

### 6. 部署业务服务

```powershell
docker stack deploy -c reproduce/docker-stack-local-services.yml docai-services
docker service ls
```

### 7. 清理 Docker 数据

如果 MySQL 初始化失败后想重来：

```powershell
docker stack rm docai-services
docker stack rm docai-infra
docker volume rm docai-infra_mysql-data
```

删除 volume 会清空数据库数据。