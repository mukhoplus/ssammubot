package com.mukho.ssammubot.config

import com.mukho.ssammubot.interceptor.ApiLoggingInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val apiLoggingInterceptor: ApiLoggingInterceptor
) : WebMvcConfigurer {
    
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("https://smweb.mukho.r-e.kr", "https://ssammubot.vercel.app", "http://localhost:5173", "http://localhost:8080")
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
    }
    
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(apiLoggingInterceptor)
            .addPathPatterns("/**") // 모든 API 경로에 적용
            .excludePathPatterns(
                "/actuator/**",  // Spring Boot Actuator 경로 제외
                "/error",        // 에러 페이지 제외
                "/favicon.ico"   // 파비콘 요청 제외
            )
    }
}