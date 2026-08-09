FROM eclipse-temurin:17-jdk

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# 维护者信息
MAINTAINER qxk

# 设定时区
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo "Asia/Shanghai" > /etc/timezone

# 复制jar包到容器内
COPY target/user-service-1.0.0.jar /app/user-service.jar

# 设定工作目录
WORKDIR /app

# 暴露端口
EXPOSE 8081

# 启动应用（添加参数，禁用MyBatis-Plus的ddlApplicationRunner）
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 CMD curl -fsS http://localhost:8081/actuator/health || exit 1
CMD ["java", "-Dmybatis-plus.global-config.db-config.ddl-auto=none", "-jar", "user-service.jar"]