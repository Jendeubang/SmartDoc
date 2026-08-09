package com.javaee.documentservice;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "document.schema.auto-migration.enabled=false"
})
class DocumentServiceApplicationTests {

    @MockBean
    private MinioClient minioClient;

    @Test
    void contextLoads() {
    }

}
