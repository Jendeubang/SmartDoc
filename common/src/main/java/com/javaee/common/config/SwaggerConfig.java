package com.javaee.common.config;

/**
 * 【简历：Swagger/OpenAPI 文档配置】
 * 配置 DocAI 系统的 API 文档信息，用于接口调试和测试。
 */

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局Swagger配置类
 * 用于配置API文档和Swagger UI
 */
@Configuration
public class SwaggerConfig {

    /**
     * 配置OpenAPI信息
     * @return OpenAPI对象
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("DocAI API")
                        .version("1.0")
                        .description("DocAI系统的API文档，用于调试和测试接口"));
    }
}
