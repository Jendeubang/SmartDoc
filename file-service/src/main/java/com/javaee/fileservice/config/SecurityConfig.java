package com.javaee.fileservice.config;

import com.javaee.common.config.security.BaseSecurityConfig;
import com.javaee.common.config.security.JwtAuthenticationFilter;
import com.javaee.fileservice.security.TrustedGatewayAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 文件服务安全配置
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig extends BaseSecurityConfig {

    private final TrustedGatewayAuthenticationFilter trustedGatewayAuthenticationFilter;

    /**
     * 构造函数注入JWT认证过滤器
     * @param jwtAuthenticationFilter JWT认证过滤器
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          TrustedGatewayAuthenticationFilter trustedGatewayAuthenticationFilter) {
        super(jwtAuthenticationFilter);
        this.trustedGatewayAuthenticationFilter = trustedGatewayAuthenticationFilter;
    }

    /**
     * 密码加密器
     * @return BCryptPasswordEncoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * BCryptPasswordEncoder实例（用于依赖注入）
     * @return BCryptPasswordEncoder
     */
    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 配置SecurityFilterChain
     * @param http HttpSecurity
     * @return SecurityFilterChain
     * @throws Exception 异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 应用基础安全配置
        applyBaseSecurityConfig(http);
        
        http
            // 授权配置
            .authorizeHttpRequests(authorize -> authorize
                // 确保Swagger相关路径的匹配规则在最前面
                .requestMatchers("/api/internal/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/v3/api-docs").hasRole("ADMIN")
                // 文件 API 需要通过 JWT 认证，不再全局放行
                // 允许静态资源访问
                .requestMatchers("/static/**", "/public/**").permitAll()
                // 允许健康检查等端点访问
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // 允许错误处理端点访问
                .requestMatchers("/error").permitAll()
                // 其他接口需要认证
                .anyRequest().authenticated()
            );

        http.addFilterAfter(trustedGatewayAuthenticationFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
