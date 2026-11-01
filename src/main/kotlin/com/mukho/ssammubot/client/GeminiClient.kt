package com.mukho.ssammubot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.mukho.ssammubot.model.SundayMapleGeminiResult
import org.slf4j.LoggerFactory
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
    @Value("\${gemini.api.key:}") private val geminiApiKey: String,
    @Value("\${gemini.api.model:}") private val preferredModel: String = ""
) : GeminiClient {

    private val logger = LoggerFactory.getLogger(GeminiClientImpl::class.java)

    @Volatile
    private var cachedFlashModels: List<String>? = null

    @Volatile
    private var modelsCacheExpiresAtMs: Long = 0L

    companion object {
        private const val MODELS_ENDPOINT = "/v1beta/models"
        private const val MAX_CANDIDATE_MODELS = 3
        private const val MODEL_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        const val SHINING_STARFORCE_HEADER = "★샤이닝 스타포스 타임★"

        private val DEFAULT_FALLBACK_MODELS = listOf(
            "gemini-3-flash-preview",
            "gemini-flash-latest",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite"
        )

        private val EXCLUDED_MODEL_REGEX = Regex("tts|image|audio|embedding", RegexOption.IGNORE_CASE)
        private val VERSION_REGEX = Regex("""gemini-(\d+(?:\.\d+)?)""")
        private val SNAPSHOT_SUFFIX_REGEX = Regex("""(-\d{3}|-\d{2}-\d{4})$""")

        private val PROMPT = """
            이 메이플스토리 '썬데이 메이플' 이벤트 이미지에서 핵심 정보를 추출해줘.
            1. 하단 유의사항, 안내 문구(회색 작은 글씨)는 모두 제외할 것.
            2. 이벤트 진행 날짜(eventDate, 예: "2026년 11월 1일(일)"), 스페셜 썬데이 여부(isSpecial), 핵심 혜택 목록(benefits)만 수치나 퍼센트(%) 오타 없이 정확히 추출할 것.
            3. 만약 이미지 내에 '샤이닝 스타포스 타임'이 포함되어 있다면, benefits 배열의 최상단(첫 번째 요소)에 반드시 "★샤이닝 스타포스 타임★"을 넣고, 그 다음 요소부터 세부 혜택을 나열할 것.
        """.trimIndent()

        fun getModelSortScore(modelName: String): Int {
            if (EXCLUDED_MODEL_REGEX.containsMatchIn(modelName)) {
                return -1000
            }

            val versionMatch = VERSION_REGEX.find(modelName)
            val versionScore = when {
                versionMatch != null -> ((versionMatch.groupValues[1].toDoubleOrNull() ?: 0.0) * 100).toInt()
                modelName == "gemini-flash-latest" -> 260
                modelName == "gemini-flash-lite-latest" -> 240
                else -> 0
            }

            var tierScore = 50
            if (modelName.contains("lite")) tierScore -= 20
            if (modelName.contains("preview") || modelName.contains("exp")) tierScore -= 5
            if (SNAPSHOT_SUFFIX_REGEX.containsMatchIn(modelName)) tierScore -= 10

            return versionScore + tierScore
        }

        fun normalizeBenefits(benefits: List<String>): List<String> {
            val cleaned = benefits
                .map { it.trim().removePrefix("-").trim() }
                .filter { it.isNotEmpty() }

            fun isShiningStarforceHeader(text: String): Boolean {
                val normalized = text.replace(Regex("[^가-힣a-zA-Z0-9]"), "")
                return normalized.contains("샤이닝스타포스타임")
            }

            if (cleaned.none(::isShiningStarforceHeader)) {
                return cleaned
            }

            val remaining = cleaned.filterNot(::isShiningStarforceHeader)

            return listOf(SHINING_STARFORCE_HEADER) + remaining
        }
    }

    override fun extractSundayMaple(imageBytes: ByteArray, mimeType: String): SundayMapleGeminiResult {
        check(geminiApiKey.isNotBlank()) { "Gemini API Key is not configured." }

        val base64Image = Base64.getEncoder().encodeToString(imageBytes)
        val requestBody = buildRequestBody(base64Image, mimeType)
        val candidateModels = getSortedFlashModels().take(MAX_CANDIDATE_MODELS)

        var lastException: Exception? = null

        for (modelName in candidateModels) {
            try {
                val responseJson = geminiRestClient.post()
                    .uri("/v1beta/models/$modelName:generateContent")
                    .header("x-goog-api-key", geminiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String::class.java)

                if (responseJson.isNullOrBlank()) {
                    logger.warn("Empty response from Gemini model: {}", modelName)
                    continue
                }

                val rootNode = objectMapper.readTree(responseJson)
                val textNode = rootNode.path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts")
                    .path(0)
                    .path("text")

                if (textNode.isMissingNode || textNode.asText().isBlank()) {
                    logger.warn("No text content in Gemini response from model: {}", modelName)
                    continue
                }

                val parsed = objectMapper.readValue(textNode.asText(), SundayMapleGeminiResult::class.java)
                return parsed.copy(benefits = normalizeBenefits(parsed.benefits))
            } catch (e: Exception) {
                logger.warn("Gemini model '{}' failed ({}), trying next fallback model.", modelName, e.message)
                lastException = e
            }
        }

        throw IllegalStateException("All Gemini Flash candidate models failed.", lastException)
    }

    internal fun getSortedFlashModels(): List<String> {
        if (preferredModel.isNotBlank()) {
            val preferred = preferredModel.removePrefix("models/").trim()
            return (listOf(preferred) + DEFAULT_FALLBACK_MODELS).distinct()
        }

        val nowMs = System.currentTimeMillis()
        cachedFlashModels?.let { cached ->
            if (nowMs < modelsCacheExpiresAtMs) {
                return cached
            }
        }

        return try {
            val responseJson = geminiRestClient.get()
                .uri(MODELS_ENDPOINT)
                .header("x-goog-api-key", geminiApiKey)
                .retrieve()
                .body(String::class.java)

            if (responseJson.isNullOrBlank()) {
                return DEFAULT_FALLBACK_MODELS
            }

            val rootNode = objectMapper.readTree(responseJson)
            val modelsNode = rootNode.path("models")
            if (!modelsNode.isArray) {
                return DEFAULT_FALLBACK_MODELS
            }

            val flashModels = modelsNode.mapNotNull { modelNode ->
                val methods = modelNode.path("supportedGenerationMethods")
                val supportsGenerate = methods.isArray && methods.any { it.asText() == "generateContent" }
                if (!supportsGenerate) return@mapNotNull null

                val rawName = modelNode.path("name").asText("").removePrefix("models/")
                if (rawName.contains("flash") && getModelSortScore(rawName) > 0) rawName else null
            }.sortedByDescending { getModelSortScore(it) }

            if (flashModels.isNotEmpty()) {
                cachedFlashModels = flashModels
                modelsCacheExpiresAtMs = nowMs + MODEL_CACHE_TTL_MS
                flashModels
            } else {
                DEFAULT_FALLBACK_MODELS
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch Gemini model list ({}), using default fallback models.", e.message)
            DEFAULT_FALLBACK_MODELS
        }
    }

    private fun buildRequestBody(base64Image: String, mimeType: String): Map<String, Any> {
        return mapOf(
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
                            "description" to "추출된 핵심 혜택 텍스트 배열 ('샤이닝 스타포스 타임' 포함 시 첫 번째 항목은 반드시 '★샤이닝 스타포스 타임★')"
                        )
                    ),
                    "required" to listOf("eventDate", "isSpecial", "benefits")
                )
            )
        )
    }
}
