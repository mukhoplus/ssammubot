package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.service.ApiLogService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service("apiLogService")
class ApiLogServiceImpl : ApiLogService {
    
    private val logger = LoggerFactory.getLogger(ApiLogServiceImpl::class.java)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    
    override fun logApiCall(
        apiName: String,
        parameters: Map<String, Any>,
        result: Any?,
        processingTime: Long,
        error: String?
    ) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val parametersStr = parameters.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val resultStr = when {
            error != null -> "ERROR: $error"
            result != null -> result.toString().take(200) // 결과를 200자로 제한
            else -> "NULL"
        }
        
        val logMessage = """
            |==================== 서비스 호출 로그 ====================
            |시간: $timestamp
            |API 이름: $apiName
            |파라미터: $parametersStr
            |처리 시간: ${processingTime}ms
            |결과: $resultStr
            |=========================================================
        """.trimMargin()
        
        if (error != null) {
            logger.error(logMessage)
        } else {
            logger.info(logMessage)
        }
    }
    
    override fun logExternalApiCall(
        externalApiUrl: String,
        parameters: Map<String, Any>,
        statusCode: Int,
        responseBody: String?,
        processingTime: Long
    ) {
        val timestamp = LocalDateTime.now().format(dateFormatter)
        val parametersStr = parameters.entries.joinToString(", ") { "${it.key}=${it.value}" }
        val responseStr = responseBody?.take(200) ?: "NULL"
        
        val logMessage = """
            |==================== 외부 API 호출 로그 ====================
            |시간: $timestamp
            |외부 API URL: $externalApiUrl
            |파라미터: $parametersStr
            |응답 상태 코드: $statusCode
            |처리 시간: ${processingTime}ms
            |응답 본문: $responseStr
            |===========================================================
        """.trimMargin()
        
        when {
            statusCode >= 500 -> logger.error(logMessage)
            statusCode >= 400 -> logger.warn(logMessage)
            else -> logger.info(logMessage)
        }
    }
}
