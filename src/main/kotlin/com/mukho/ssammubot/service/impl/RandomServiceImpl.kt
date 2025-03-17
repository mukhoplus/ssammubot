package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.model.ResponseDto
import com.mukho.ssammubot.model.VsDto
import com.mukho.ssammubot.service.RandomService
import com.mukho.ssammubot.utils.ClassUtil
import com.mukho.ssammubot.utils.FoodUtil
import org.springframework.stereotype.Service

@Service("randomService")
class RandomServiceImpl: RandomService {

    override fun food(): ResponseDto {
        val message = FoodUtil.randomFood()
        return ResponseDto(message)
    }

    override fun classRecommand(): ResponseDto {
        val message = ClassUtil.randomClass()
        return ResponseDto(message)
    }

    override fun vs(vsDto: VsDto): ResponseDto {
        val message = vsDto.options.random()
        return ResponseDto(message)
    }

    override fun dice(): ResponseDto {
        val message = (1..6).random().toString()
        return ResponseDto(message)
    }
}