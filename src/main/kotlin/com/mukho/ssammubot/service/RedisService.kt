package com.mukho.ssammubot.service

import java.time.Duration

interface RedisService {
    fun saveOcid(characterName: String, ocid: String)
    fun getOcid(characterName: String): String?
    fun saveHistory(characterName: String, date: String, value: String)
    fun getHistory(characterName: String, date: String): String?
}