FROM eclipse-temurin:17-jdk

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --system smartdoc && useradd --system --gid smartdoc --home-dir /app smartdoc

# 维护者信息
MAINTAINER qxk

# 设定时区
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo "Asia/Shanghai" > /etc/timezone

# 复制jar包到容器内
COPY target/ai-service-1.0.0.jar /app/ai-service.jar

# 设定工作目录
WORKDIR /app
RUN chown -R smartdoc:smartdoc /app
USER smartdoc

# 暴露端口
EXPOSE 8083

# 启动应用
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 CMD curl -fsS http://localhost:8083/actuator/health || exit 1
CMD ["java", "-jar", "ai-service.jar"]
