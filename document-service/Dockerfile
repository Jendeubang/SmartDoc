FROM eclipse-temurin:17-jdk

MAINTAINER qxk

RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo "Asia/Shanghai" > /etc/timezone

# Tesseract provides Chinese/English OCR. LibreOffice Writer performs headless DOCX -> PDF conversion.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-chi-sim \
        libreoffice-writer \
        fonts-noto-cjk \
        curl \
    && rm -rf /var/lib/apt/lists/*

ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

COPY target/document-service-1.0.0.jar /app/document-service.jar
WORKDIR /app
EXPOSE 8084
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=5 CMD curl -fsS http://localhost:8084/actuator/health || exit 1
CMD ["java", "-jar", "document-service.jar"]