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

    fun getDuration(date: String): Duration {
        val diff = ChronoUnit.DAYS.between(LocalDate.parse(date), LocalDate.now())
        return Duration.ofDays((historyTTL.toDays() - diff).coerceAtLeast(1))
    }
}