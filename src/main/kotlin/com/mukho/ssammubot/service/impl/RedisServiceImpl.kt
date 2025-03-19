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

    override fun saveOcid(username: String, ocid: String) {
        redisTemplate.opsForHash<String, String>().put("ocid", username, ocid)
        redisTemplate.expire("ocid", ocidTTL) // TTL 7일 적용
    }

    override fun getOcid(username: String): String? {
        val ocid = redisTemplate.opsForHash<String, String>().get("ocid", username)

        // 조회 시 TTL 연장
        if (ocid != null) {
            redisTemplate.expire("ocid", ocidTTL)
        }

        return ocid
    }

    override fun saveHistory(username: String, date: String, value: String) {
        val key = "history:$date"
        val duration = getDuration(date)

        redisTemplate.opsForHash<String, String>().put(key, username, value)
        redisTemplate.expire(key, duration)
    }

    override fun getHistory(username: String, date: String): Map<String, String>? {
        val key = "history:$date"
        return redisTemplate.opsForHash<String, String>().entries(key)
    }

    fun getDuration(date: String): Duration {
        val diff = ChronoUnit.DAYS.between(LocalDate.parse(date), LocalDate.now())
        return Duration.ofDays((historyTTL.toDays() - diff).coerceAtLeast(1))
    }
}