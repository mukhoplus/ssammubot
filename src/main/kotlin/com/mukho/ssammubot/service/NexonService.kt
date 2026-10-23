package com.mukho.ssammubot.service

import com.mukho.ssammubot.model.ResponseDto

interface NexonService {
    fun scouter(characterName: String): ResponseDto
    fun info(characterName: String): ResponseDto
    fun history(characterName: String): ResponseDto
    fun symbol(characterName: String): ResponseDto
    fun abil(characterName: String): ResponseDto
    fun levelUp(characterName: String, targetLevel: Int? = null): ResponseDto
    fun ring(characterName: String): ResponseDto
    fun scheduler(characterName: String, apiKey: String? = null): ResponseDto
}