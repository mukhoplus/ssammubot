package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.service.RedisService
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Service("redisService")
class RedisServiceImpl(
    private val redisTemplate: RedisTemplate<String, String>
): RedisService {
    private val ocidTTL = Duration.ofDays(7)
    private val historyTTL = Duration.ofDays(9)

    override fun saveOcid(characterName: String, ocid: String) {
        redisTemplate.opsForValue().set("ocid:$characterName", ocid, ocidTTL)
    }

    override fun getOcid(characterName: String): String? {
        val key = "ocid:$characterName"
        val ocid = redisTemplate.opsForValue().get(key)

        // 조회 시 TTL 연장
        if (ocid != null) {
            redisTemplate.expire(key, ocidTTL)
        }

        return ocid
    }

    override fun saveHistory(characterName: String, date: String, value: String) {
        val key = "history:$date"
        val duration = getDuration(date)

        redisTemplate.opsForHash<String, String>().put(key, characterName, value)
        redisTemplate.expire(key, duration)
    }

    override fun getHistory(characterName: String, date: String): String? {
        val key = "history:$date"
        return redisTemplate.opsForHash<String, String>().get(key, characterName)
    }

    override fun saveLevelExp(characterName: String, date: String, level: Int, exp: Long) {
        val levelKey = "level:$date"
        val expKey = "exp:$date"
        val duration = getDuration(date)

        redisTemplate.opsForHash<String, String>().put(levelKey, characterName, level.toString())
        redisTemplate.expire(levelKey, duration)
        
        redisTemplate.opsForHash<String, String>().put(expKey, characterName, exp.toString())
        redisTemplate.expire(expKey, duration)
    }

    override fun getLevelExp(characterName: String, date: String): Pair<Int, Long>? {
        val levelKey = "level:$date"
        val expKey = "exp:$date"
        
        val levelStr = redisTemplate.opsForHash<String, String>().get(levelKey, characterName)
        val expStr = redisTemplate.opsForHash<String, String>().get(expKey, characterName)
        
        return if (levelStr != null && expStr != null) {
            try {
                Pair(levelStr.toInt(), expStr.toLong())
            } catch (e: NumberFormatException) {
                null
            }
        } else {
            null
        }
    }

    override fun getLevelExpHistory(characterName: String, dates: List<String>): List<Pair<Int, Long>> {
        val result = mutableListOf<Pair<Int, Long>>()
        
        for (date in dates) {
            val levelExp = getLevelExp(characterName, date)
            if (levelExp != null) {
                result.add(levelExp)
            }
        }
        
        return result
    }

    fun getDuration(date: String): Duration {
        val diff = ChronoUnit.DAYS.between(LocalDate.parse(date), LocalDate.now())
        return Duration.ofDays((historyTTL.toDays() - diff).coerceAtLeast(1))
    }
}