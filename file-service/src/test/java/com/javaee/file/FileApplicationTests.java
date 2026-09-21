package com.javaee.file;

import com.javaee.fileservice.FileServiceApplication;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(classes = FileServiceApplication.class, properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false"
})
class FileApplicationTests {

    @MockBean
    private MinioClient minioClient;

    @Test
    void contextLoads() {
    }

}
