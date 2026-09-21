# syntax=docker/dockerfile:1.7

ARG RUNTIME_TARGET=runtime-base

FROM maven:3.9.9-eclipse-temurin-17 AS build

ARG SERVICE_MODULE
WORKDIR /workspace
COPY . .

# Compile only the selected service and its shared Maven modules.
RUN mvn -B -DskipTests -Dcheckstyle.skip=true -pl "${SERVICE_MODULE}" -am package

FROM eclipse-temurin:17-jre AS runtime-base

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system smartdoc \
    && useradd --system --gid smartdoc --home-dir /app smartdoc

RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

WORKDIR /app

FROM runtime-base AS document-runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-chi-sim \
        libreoffice-writer \
        fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

FROM ${RUNTIME_TARGET} AS runtime

ARG SERVICE_MODULE
ARG SERVICE_JAR
ARG SERVICE_PORT

COPY --from=build /workspace/${SERVICE_MODULE}/target/${SERVICE_JAR}.jar /app/app.jar
RUN chown -R smartdoc:smartdoc /app
USER smartdoc

ENV SERVICE_PORT=${SERVICE_PORT}
EXPOSE ${SERVICE_PORT}
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 \
    CMD curl -fsS http://localhost:${SERVICE_PORT}/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
