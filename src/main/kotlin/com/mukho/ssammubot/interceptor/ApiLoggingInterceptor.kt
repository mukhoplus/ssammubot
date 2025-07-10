package com.mukho.ssammubot.interceptor

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

@Component
class ApiLoggingInterceptor(
    private val objectMapper: ObjectMapper
) : HandlerInterceptor {
    
    private val logger = LoggerFactory.getLogger(ApiLoggingInterceptor::class.java)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val startTime = System.currentTimeMillis()
        request.setAttribute("startTime", startTime)
        
        // 요청 정보 로깅
        val requestTime = LocalDateTime.now().format(dateFormatter)
        val method = request.method
        val uri = request.requestURI
        val queryString = request.queryString ?: ""
        val clientIp = getClientIp(request)
        
        val logMessage = """
            |==================== API 요청 ====================
            |시간: $requestTime
            |클라이언트 IP: $clientIp
            |HTTP 메서드: $method
            |요청 URI: $uri
            |쿼리 파라미터: $queryString
            |User-Agent: ${request.getHeader("User-Agent") ?: "Unknown"}
            |==================================================
        """.trimMargin()
        
        logger.info(logMessage)
        
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        val startTime = request.getAttribute("startTime") as? Long ?: System.currentTimeMillis()
        val endTime = System.currentTimeMillis()
        val processingTime = endTime - startTime
        
        val responseTime = LocalDateTime.now().format(dateFormatter)
        val method = request.method
        val uri = request.requestURI
        val queryString = request.queryString ?: ""
        val statusCode = response.status
        val clientIp = getClientIp(request)
        
        // 응답 상태에 따른 로그 레벨 결정
        val logLevel = when {
            statusCode >= 500 -> "ERROR"
            statusCode >= 400 -> "WARN"
            else -> "INFO"
        }
        
        val logMessage = """
            |==================== API 응답 ====================
            |시간: $responseTime
            |클라이언트 IP: $clientIp
            |HTTP 메서드: $method
            |요청 URI: $uri
            |쿼리 파라미터: $queryString
            |응답 상태: $statusCode
            |처리 시간: ${processingTime}ms
            |로그 레벨: $logLevel
            |${if (ex != null) "예외: ${ex.message}" else ""}
            |==================================================
        """.trimMargin()
        
        when (logLevel) {
            "ERROR" -> logger.error(logMessage, ex)
            "WARN" -> logger.warn(logMessage)
            else -> logger.info(logMessage)
        }
    }
    
    private fun getClientIp(request: HttpServletRequest): String {
        val xForwardedFor = request.getHeader("X-Forwarded-For")
        val xRealIp = request.getHeader("X-Real-IP")
        
        return when {
            !xForwardedFor.isNullOrEmpty() -> xForwardedFor.split(",")[0].trim()
            !xRealIp.isNullOrEmpty() -> xRealIp
            else -> request.remoteAddr
        }
    }
}
