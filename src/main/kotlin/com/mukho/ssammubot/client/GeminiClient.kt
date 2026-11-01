package com.mukho.ssammubot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.mukho.ssammubot.model.SundayMapleGeminiResult
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.Base64

fun interface GeminiClient {
    fun extractSundayMaple(imageBytes: ByteArray, mimeType: String): SundayMapleGeminiResult
}

@Component
class GeminiClientImpl(
    @Qualifier("geminiRestClient") private val geminiRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    @Value("\${gemini.api.key:}") private val geminiApiKey: String
) : GeminiClient {

    companion object {
        private const val GEMINI_ENDPOINT = "/v1beta/models/gemini-2.5-flash:generateContent"
        private val PROMPT = """
            이 메이플스토리 '썬데이 메이플' 이벤트 이미지에서 핵심 정보를 추출해줘.
            1. 하단 유의사항, 안내 문구(회색 작은 글씨)는 모두 제외할 것.
            2. 이벤트 진행 날짜(eventDate, 예: "2026년 11월 1일(일)"), 스페셜 썬데이 여부(isSpecial), 핵심 혜택 목록(benefits)만 수치나 퍼센트(%) 오타 없이 정확히 추출할 것.
        """.trimIndent()
    }

    override fun extractSundayMaple(imageBytes: ByteArray, mimeType: String): SundayMapleGeminiResult {
        check(geminiApiKey.isNotBlank()) { "Gemini API Key is not configured." }

        val base64Image = Base64.getEncoder().encodeToString(imageBytes)
        val requestBody = mapOf(
            "contents" to listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to PROMPT),
                        mapOf(
                            "inlineData" to mapOf(
                                "mimeType" to mimeType,
                                "data" to base64Image
                            )
                        )
                    )
                )
            ),
            "generationConfig" to mapOf(
                "responseMimeType" to "application/json",
                "responseSchema" to mapOf(
                    "type" to "OBJECT",
                    "properties" to mapOf(
                        "eventDate" to mapOf(
                            "type" to "STRING",
                            "description" to "이벤트 진행 날짜 (예: 2026년 11월 1일(일))"
                        ),
                        "isSpecial" to mapOf(
                            "type" to "BOOLEAN",
                            "description" to "스페셜 썬데이 메이플 여부"
                        ),
                        "benefits" to mapOf(
                            "type" to "ARRAY",
                            "items" to mapOf("type" to "STRING"),
                            "description" to "추출된 핵심 혜택 텍스트 배열 (유의사항 제외)"
                        )
                    ),
                    "required" to listOf("eventDate", "isSpecial", "benefits")
                )
            )
        )

        val responseJson = geminiRestClient.post()
            .uri(GEMINI_ENDPOINT)
            .header("x-goog-api-key", geminiApiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Empty response from Gemini API.")

        val rootNode = objectMapper.readTree(responseJson)
        val textNode = rootNode.path("candidates")
            .path(0)
            .path("content")
            .path("parts")
            .path(0)
            .path("text")

        if (textNode.isMissingNode || textNode.asText().isBlank()) {
            throw IllegalStateException("No text content found in Gemini API response.")
        }

        return objectMapper.readValue(textNode.asText(), SundayMapleGeminiResult::class.java)
    }
}
