FROM eclipse-temurin:17-jdk

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --system smartdoc && useradd --system --gid smartdoc --home-dir /app smartdoc
WORKDIR /app
COPY target/file-service-1.0.0.jar /app/file-service.jar
RUN chown -R smartdoc:smartdoc /app
USER smartdoc
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 CMD curl -fsS http://localhost:8082/actuator/health || exit 1
CMD ["java", "-jar", "file-service.jar"]
