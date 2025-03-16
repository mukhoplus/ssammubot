package com.mukho.ssammubot.service

import com.mukho.ssammubot.model.ResponseDto

interface BasicService {
    fun help(): ResponseDto
    fun ssammu(): ResponseDto
}