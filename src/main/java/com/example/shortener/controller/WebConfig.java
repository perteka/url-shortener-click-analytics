package com.example.shortener.controller;

import com.example.shortener.config.AppProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final ApiKeyInterceptor interceptor;
    private final AppProperties properties;

    public WebConfig(ApiKeyInterceptor interceptor, AppProperties properties) {
        this.interceptor = interceptor;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = properties.corsAllowedOrigins();
        var registration = registry.addMapping("/api/**")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Accept", "Content-Type", "Origin", "X-API-Key", "X-Request-Id")
                .exposedHeaders("Retry-After", "X-Request-Id", "Location")
                .maxAge(3600);
        if (origins.contains("*")) {
            registration.allowedOriginPatterns("*");
        } else {
            registration.allowedOrigins(origins.toArray(String[]::new));
        }
    }
}
