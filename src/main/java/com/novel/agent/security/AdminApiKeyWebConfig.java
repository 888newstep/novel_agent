package com.novel.agent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminApiKeyWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<AdminApiKeyInterceptor> adminApiKeyInterceptorProvider;

    public AdminApiKeyWebConfig(ObjectProvider<AdminApiKeyInterceptor> adminApiKeyInterceptorProvider) {
        this.adminApiKeyInterceptorProvider = adminApiKeyInterceptorProvider;
    }

    @Bean
    @ConfigurationProperties(prefix = "app.security")
    public AdminSecurityProperties adminSecurityProperties() {
        return new AdminSecurityProperties();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminApiKeyInterceptorProvider.getObject())
                .addPathPatterns("/api/v1/novel/admin/**", "/api/import/finalize");
    }
}
