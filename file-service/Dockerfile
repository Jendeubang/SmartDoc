FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY target/file-service-1.0.0.jar /app/file-service.jar
EXPOSE 8082
CMD ["java", "-jar", "file-service.jar"]