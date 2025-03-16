package com.mukho.ssammubot.service

import com.mukho.ssammubot.model.ResponseDto

interface NexonService {
    fun scouter(characterName: String): ResponseDto
    fun info(characterName: String): ResponseDto
    fun history(characterName: String): ResponseDto
}