package com.mukho.ssammubot.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.mukho.ssammubot.model.SundayMapleGeminiResult
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.Base64
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    private val threadSequence = AtomicInteger(0)
    private val geminiExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_CANDIDATE_MODELS) { runnable ->
        Thread(runnable, "gemini-sunday-${threadSequence.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile
    private var cachedFlashModels: List<String>? = null

    @Volatile
    private var modelsCacheExpiresAtMs: Long = 0L

    @PreDestroy
    fun shutdownGeminiExecutor() {
        geminiExecutor.shutdown()
    }

    companion object {
        private const val MODELS_ENDPOINT = "/v1beta/models"
        private const val MAX_CANDIDATE_MODELS = 3
        private const val CONCURRENT_TIMEOUT_SECONDS = 25L
        private const val MODEL_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        const val SHINING_STARFORCE_HEADER = "★샤이닝 스타포스 타임★"

        private val DEFAULT_FALLBACK_MODELS = listOf(
            "gemini-4-hydrogen",
            "gemini-3-flash-preview",
            "gemini-flash-latest",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite"
        )

        private val EXCLUDED_MODEL_REGEX = Regex("tts|image|audio|embedding", RegexOption.IGNORE_CASE)
        private val LIGHTWEIGHT_MODEL_REGEX = Regex("flash|hydrogen", RegexOption.IGNORE_CASE)
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

        fun isCandidateModel(modelName: String): Boolean =
            LIGHTWEIGHT_MODEL_REGEX.containsMatchIn(modelName) && getModelSortScore(modelName) > 0

        internal fun resolveThinkingConfig(modelName: String): Map<String, Any>? {
            val majorVersion = VERSION_REGEX.find(modelName)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return null
            return if (majorVersion >= 3.0) {
                mapOf("thinkingLevel" to "LOW")
            } else {
                null
            }
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
        val candidateModels = getSortedFlashModels().take(MAX_CANDIDATE_MODELS)

        val completionService: CompletionService<SundayMapleGeminiResult> =
            ExecutorCompletionService(geminiExecutor)
        val futures = mutableListOf<Future<SundayMapleGeminiResult>>()
        var lastException: Exception? = null

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONCURRENT_TIMEOUT_SECONDS)

        try {
            for (modelName in candidateModels) {
                futures.add(
                    completionService.submit {
                        requestSingleModel(modelName, base64Image, mimeType)
                    }
                )
            }

            repeat(futures.size) {
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) {
                    throw IllegalStateException(
                        "Gemini candidate models timed out after ${CONCURRENT_TIMEOUT_SECONDS}s.",
                        lastException
                    )
                }

                val completedFuture = completionService.poll(remainingNanos, TimeUnit.NANOSECONDS)
                    ?: throw IllegalStateException(
                        "Gemini candidate models timed out after ${CONCURRENT_TIMEOUT_SECONDS}s.",
                        lastException
                    )

                try {
                    return completedFuture.get()
                } catch (e: ExecutionException) {
                    val cause = (e.cause as? Exception) ?: e
                    lastException = cause
                } catch (e: Exception) {
                    lastException = e
                }
            }
        } finally {
            futures.forEach { it.cancel(true) }
        }

        throw IllegalStateException("All Gemini candidate models failed.", lastException)
    }

    private fun requestSingleModel(
        modelName: String,
        base64Image: String,
        mimeType: String
    ): SundayMapleGeminiResult {
        return try {
            val requestBody = buildRequestBody(base64Image, mimeType, modelName)
            val responseJson = geminiRestClient.post()
                .uri("/v1beta/models/$modelName:generateContent")
                .header("x-goog-api-key", geminiApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String::class.java)

            if (responseJson.isNullOrBlank()) {
                logger.warn("Empty response from Gemini model: {}", modelName)
                throw IllegalStateException("Empty response from Gemini model: $modelName")
            }

            val rootNode = objectMapper.readTree(responseJson)
            val partsNode = rootNode.path("candidates")
                .path(0)
                .path("content")
                .path("parts")

            val textContent = if (partsNode.isArray) {
                partsNode.firstOrNull { part ->
                    !part.path("thought").asBoolean(false) && part.path("text").asText().isNotBlank()
                }?.path("text")?.asText()
                    ?: partsNode.path(0).path("text").asText("")
            } else {
                ""
            }

            if (textContent.isBlank()) {
                logger.warn("No text content in Gemini response from model: {}", modelName)
                throw IllegalStateException("No text content in Gemini response from model: $modelName")
            }

            val parsed = objectMapper.readValue(textContent, SundayMapleGeminiResult::class.java)
            parsed.copy(benefits = normalizeBenefits(parsed.benefits))
        } catch (e: Exception) {
            logger.warn("Gemini model '{}' failed ({}).", modelName, e.message)
            throw e
        }
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
                if (isCandidateModel(rawName)) rawName else null
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

    internal fun buildRequestBody(base64Image: String, mimeType: String, modelName: String = ""): Map<String, Any> {
        val generationConfig = mutableMapOf<String, Any>(
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

        resolveThinkingConfig(modelName)?.let { thinkingConfig ->
            generationConfig["thinkingConfig"] = thinkingConfig
        }

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
            "generationConfig" to generationConfig
        )
    }
}
