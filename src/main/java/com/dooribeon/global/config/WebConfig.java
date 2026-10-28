package com.dooribeon.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 프론트(다른 출처)에서 API 응답을 읽을 수 있게 허용 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:5173",           // 로컬 프론트 (Vite)
                        "https://dooribeon.vercel.app"     // 배포 프론트
                )
                .allowedMethods("GET", "POST", "PUT");
    }
}