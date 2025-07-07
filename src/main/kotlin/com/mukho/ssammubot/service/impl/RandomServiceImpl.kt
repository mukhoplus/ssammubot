package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.model.ResponseDto
import com.mukho.ssammubot.model.VsDto
import com.mukho.ssammubot.service.ApiLogService
import com.mukho.ssammubot.service.RandomService
import com.mukho.ssammubot.utils.ClassUtil
import com.mukho.ssammubot.utils.FoodUtil
import org.springframework.stereotype.Service

@Service("randomService")
class RandomServiceImpl(
    private val apiLogService: ApiLogService
): RandomService {

    override fun food(): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = emptyMap<String, Any>()
        
        return try {
            val message = FoodUtil.randomFood()
            val result = ResponseDto(message)
            
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("food", parameters, result, processingTime)
            
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("food", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun classRecommand(): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = emptyMap<String, Any>()
        
        return try {
            val message = ClassUtil.randomClass()
            val result = ResponseDto(message)
            
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("classRecommand", parameters, result, processingTime)
            
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("classRecommand", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun vs(vsDto: VsDto): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("options" to vsDto.options)
        
        return try {
            val message = vsDto.options.random()
            val result = ResponseDto(message)
            
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("vs", parameters, result, processingTime)
            
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("vs", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun dice(): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = emptyMap<String, Any>()
        
        return try {
            val message = (1..6).random().toString()
            val result = ResponseDto(message)
            
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("dice", parameters, result, processingTime)
            
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("dice", parameters, null, processingTime, e.message)
            throw e
        }
    }
}