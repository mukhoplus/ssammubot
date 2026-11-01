package com.mukho.ssammubot.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime
import java.time.ZoneId

@JsonIgnoreProperties(ignoreUnknown = true)
data class SundayMapleGeminiResult(
    @field:JsonProperty("eventDate")
    @get:JsonProperty("eventDate")
    @param:JsonProperty("eventDate")
    val eventDate: String = "",

    @field:JsonProperty("isSpecial")
    @get:JsonProperty("isSpecial")
    @param:JsonProperty("isSpecial")
    val isSpecial: Boolean = false,

    @field:JsonProperty("benefits")
    @get:JsonProperty("benefits")
    @param:JsonProperty("benefits")
    val benefits: List<String> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SundayMapleInfo(
    @field:JsonProperty("noticeId")
    @get:JsonProperty("noticeId")
    @param:JsonProperty("noticeId")
    val noticeId: Long = 0L,

    @field:JsonProperty("title")
    @get:JsonProperty("title")
    @param:JsonProperty("title")
    val title: String = "",

    @field:JsonProperty("url")
    @get:JsonProperty("url")
    @param:JsonProperty("url")
    val url: String = "",

    @field:JsonProperty("imageUrl")
    @get:JsonProperty("imageUrl")
    @param:JsonProperty("imageUrl")
    val imageUrl: String = "",

    @field:JsonProperty("eventDate")
    @get:JsonProperty("eventDate")
    @param:JsonProperty("eventDate")
    val eventDate: String = "",

    @field:JsonProperty("isSpecial")
    @get:JsonProperty("isSpecial")
    @param:JsonProperty("isSpecial")
    val isSpecial: Boolean = false,

    @field:JsonProperty("benefits")
    @get:JsonProperty("benefits")
    @param:JsonProperty("benefits")
    val benefits: List<String> = emptyList(),

    @field:JsonProperty("eventEndAt")
    @get:JsonProperty("eventEndAt")
    @param:JsonProperty("eventEndAt")
    val eventEndAt: String = "",

    @field:JsonProperty("cachedAt")
    @get:JsonProperty("cachedAt")
    @param:JsonProperty("cachedAt")
    val cachedAt: String = "",

    @field:JsonProperty("expiresAt")
    @get:JsonProperty("expiresAt")
    @param:JsonProperty("expiresAt")
    val expiresAt: String = ""
) {
    fun isValidNow(now: LocalDateTime = LocalDateTime.now(KST)): Boolean {
        return try {
            val expiresAtTime = LocalDateTime.parse(expiresAt)
            now.isBefore(expiresAtTime) && benefits.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
