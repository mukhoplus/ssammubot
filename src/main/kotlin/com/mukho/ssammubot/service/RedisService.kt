package com.mukho.ssammubot.service

import java.time.Duration

interface RedisService {
    fun saveOcid(username: String, ocid: String)
    fun getOcid(username: String): String?
    fun saveHistory(username: String, date: String, value: String)
    fun getHistory(username: String, date: String): Map<String, String>?
}