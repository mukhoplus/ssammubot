package com.mukho.ssammubot.service

interface ApiLogService {
    
    fun logApiCall(
        apiName: String,
        parameters: Map<String, Any?>,
        result: Any?,
        processingTime: Long,
        error: String? = null
    )
    
    fun logExternalApiCall(
        externalApiUrl: String,
        parameters: Map<String, Any>,
        statusCode: Int,
        responseBody: String?,
        processingTime: Long
    )
}
