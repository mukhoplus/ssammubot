package com.mukho.ssammubot.service

import com.mukho.ssammubot.model.ResponseDto
import com.mukho.ssammubot.model.VsDto

interface RandomService {
    fun food(): ResponseDto
    fun classRecommend(): ResponseDto
    fun vs(vsDto: VsDto): ResponseDto
    fun dice(): ResponseDto
    fun playlist(): ResponseDto
    fun lotto(): ResponseDto
}