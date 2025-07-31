package com.mukho.ssammubot.service

import java.time.Duration

interface RedisService {
    fun saveOcid(characterName: String, ocid: String)
    fun getOcid(characterName: String): String?
    fun saveHistory(characterName: String, date: String, value: String)
    fun getHistory(characterName: String, date: String): String?
    
    // 레벨/경험치 정보 저장 및 조회
    fun saveLevelExp(characterName: String, date: String, level: Int, exp: Long)
    fun getLevelExp(characterName: String, date: String): Pair<Int, Long>?
    fun getLevelExpHistory(characterName: String, dates: List<String>): List<Pair<Int, Long>>
}