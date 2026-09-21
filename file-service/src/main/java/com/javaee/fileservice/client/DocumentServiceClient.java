package com.javaee.fileservice.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * 文档服务 Feign 客户端
 * 上传文件后自动创建文档记录
 */
@FeignClient(name = "document", url = "${document.service.url:http://localhost:8084}")
public interface DocumentServiceClient {

    /**
     * 创建文档记录
     * @param request { title, fileId, bucketName, objectName, category }
     * @return 文档信息
     */
    @PostMapping("/api/documents")
    Map<String, Object> createDocument(@RequestBody Map<String, Object> request);
}
