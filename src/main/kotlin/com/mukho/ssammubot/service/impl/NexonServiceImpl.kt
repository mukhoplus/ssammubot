package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.model.*
import com.mukho.ssammubot.service.ApiLogService
import com.mukho.ssammubot.service.NexonService
import com.mukho.ssammubot.service.RedisService
import com.mukho.ssammubot.utils.CryptoUtil
import com.mukho.ssammubot.utils.ExperienceUtil
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.compareTo
import kotlin.math.ceil

@Service("nexonService")
class NexonServiceImpl(
    private val webClient: WebClient,
    private val redisService: RedisService,
    private val apiLogService: ApiLogService
): NexonService {

    private fun isErrorResponse(ocid: String): Boolean {
        return ocid.startsWith("닉네임을 다시 확인해주세요") ||
               ocid.startsWith("API 오류 발생") ||
               ocid.startsWith("사용량이 많습니다. 다시 시도해주세요.") ||
               ocid.startsWith("Nexon API 서버 오류 발생")
    }

    private fun formatErrorMessage(ocid: String): String {
        return if (ocid.startsWith("닉네임을 다시 확인해주세요")) "닉네임을 다시 확인해주세요." else ocid
    }

    override fun scouter(characterName: String): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)
        
        return try {
            var ocid = fetchOcid(characterName)
            
            if (isErrorResponse(ocid)) {
                return ResponseDto(formatErrorMessage(ocid))
            }
            
            val result = ResponseDto("https://maplescouter.com/info?name=$characterName")
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("scouter", parameters, result, processingTime)
            
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("scouter", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun info(characterName: String): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)
        
        return try {
            var ocid = fetchOcid(characterName)
            
            val result = if (isErrorResponse(ocid)) {
                ResponseDto(formatErrorMessage(ocid))
            } else {
                var infoResultStr = fetchInfoResult(ocid, characterName)
                
                // OPENAPI00003 등으로 인해 닉네임 확인 메시지가 반환된 경우 1회 자동 재시도
                if (infoResultStr.startsWith("닉네임을 다시 확인해주세요")) {
                    ocid = fetchOcid(characterName, forceRefresh = true)
                    if (!isErrorResponse(ocid)) {
                        infoResultStr = fetchInfoResult(ocid, characterName)
                    }
                }
                ResponseDto(infoResultStr)
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("info", parameters, result, processingTime)
            
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("info", parameters, null, processingTime, e.message)
            throw e
        }
    }

    private fun fetchInfoResult(ocid: String, characterName: String): String {
        return try {
            Mono.zip(getInfo(ocid, characterName), getStat(ocid, characterName), getUnion(ocid))
                .map { tuple ->
                    val infoResult = tuple.t1
                    val statResult = tuple.t2
                    val unionResult = tuple.t3
                    
                    when {
                        infoResult.startsWith("닉네임을 다시 확인해주세요") -> "닉네임을 다시 확인해주세요"
                        statResult.startsWith("닉네임을 다시 확인해주세요") -> "닉네임을 다시 확인해주세요"
                        infoResult.startsWith("정보 - 서버 점검 중에는 이용 불가합니다.") -> "정보 - 서버 점검 중에는 이용 불가합니다."
                        infoResult.startsWith("2023년 12월 21일 이후의 접속 기록이 없습니다.") -> "2023년 12월 21일 이후의 접속 기록이 없습니다."
                        infoResult.startsWith("API 오류 발생") -> "API 오류 발생"
                        infoResult.startsWith("사용량이 많습니다. 다시 시도해주세요") -> "사용량이 많습니다. 다시 시도해주세요."
                        infoResult.startsWith("Nexon API 서버 오류 발생") -> "Nexon API 서버 오류 발생"
                        statResult.startsWith("2023년 12월 21일 이후의 접속 기록이 없습니다.") -> "2023년 12월 21일 이후의 접속 기록이 없습니다."
                        statResult.startsWith("API 오류 발생") -> "API 오류 발생"
                        statResult.startsWith("사용량이 많습니다. 다시 시도해주세요") -> "사용량이 많습니다. 다시 시도해주세요."
                        statResult.startsWith("Nexon API 서버 오류 발생") -> "Nexon API 서버 오류 발생"
                        else -> infoResult + statResult + unionResult
                    }
                }
                .block() ?: "API 오류 발생"
        } catch (e: Exception) {
            "API 오류 발생"
        }
    }

    override fun history(characterName: String): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)
        return try {
            var ocid = fetchOcid(characterName)
            if (isErrorResponse(ocid)) {
                return ResponseDto(formatErrorMessage(ocid))
            }

            var historyStr = fetchHistoryResult(ocid, characterName)
            if (historyStr.startsWith("닉네임을 다시 확인해주세요")) {
                ocid = fetchOcid(characterName, forceRefresh = true)
                if (!isErrorResponse(ocid)) {
                    historyStr = fetchHistoryResult(ocid, characterName)
                }
            }

            val result = ResponseDto(historyStr)
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("history", parameters, result, processingTime)
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("history", parameters, null, processingTime, e.message)
            throw e
        }
    }

    private fun fetchHistoryResult(ocid: String, characterName: String): String {
        return try {
            val lastWeekDates = getLastWeekDates()
            val expData: MutableList<String> = mutableListOf()
            var isValidId = true
            var charWorldInfo: String? = null

            for (date in lastWeekDates) {
                val cachedCharacterBasic: String? = redisService.getHistory(characterName, date)
                if (cachedCharacterBasic != null) {
                    if (charWorldInfo == null) {
                        val historyFromApi = getHistory(ocid, date, characterName).block()
                        charWorldInfo = historyFromApi?.getOrNull(0) ?: ""
                    }
                    expData.add(cachedCharacterBasic)
                } else {
                    val characterBasic: List<String>? = getHistory(ocid, date, characterName).block()
                    if (!characterBasic.isNullOrEmpty()) {
                        if (characterBasic.size == 1) {
                            if (characterBasic[0].startsWith("닉네임을 다시 확인해주세요")) {
                                return "닉네임을 다시 확인해주세요"
                            }
                            if (characterBasic[0].startsWith("2023년")) {
                                isValidId = false
                                break
                            }
                        }
                        if (charWorldInfo == null) {
                            charWorldInfo = characterBasic[0]
                        }
                        expData.add(characterBasic[1])
                    }
                }
            }

            val levelExpHistory = getOrFetchLevelExpHistory(characterName, ocid, lastWeekDates)

            var levelUpEstimateMsg = ""
            if (levelExpHistory.size >= 2) {
                val avgExpGain = ExperienceUtil.calculateAverageExpGain(levelExpHistory)
                val (curLevel, curExp) = levelExpHistory.last()
                val expToNext = ExperienceUtil.getExpToNextLevel(curLevel, curExp)
                if (avgExpGain > 0 && expToNext > 0) {
                    val days = ceil((expToNext).toDouble() / avgExpGain).toInt()
                    val targetDate = LocalDate.now().plusDays(days.toLong())
                    val formattedDate = targetDate.format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"))
                    levelUpEstimateMsg = "예상 레벨업 날짜 : $formattedDate(${days}일 후)"
                }
            }

            buildString {
                if (expData.isEmpty()) {
                    if (isValidId) {
                        append("히스토리 데이터가 없습니다.")
                    } else {
                        append("2023년 12월 21일 이후의 접속 기록이 없습니다.")
                    }
                } else {
                    if (!charWorldInfo.isNullOrBlank()) {
                        append(charWorldInfo)
                        append("\n")
                    }
                    expData.forEach {
                        append(it)
                        append("\n")
                    }
                    append(levelUpEstimateMsg)
                }
            }
        } catch (e: Exception) {
            "API 오류 발생"
        }
    }

    override fun symbol(characterName: String): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)
        
        return try {
            var ocid = fetchOcid(characterName)
            
            if (isErrorResponse(ocid)) {
                return ResponseDto(formatErrorMessage(ocid))
            }

            var symbolResult = getSymbol(characterName, ocid).block() ?: "API 오류 발생"
            if (symbolResult.startsWith("닉네임을 다시 확인해주세요")) {
                ocid = fetchOcid(characterName, forceRefresh = true)
                if (!isErrorResponse(ocid)) {
                    symbolResult = getSymbol(characterName, ocid).block() ?: "API 오류 발생"
                }
            }
            
            val result = ResponseDto(symbolResult)
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("symbol", parameters, result, processingTime)
            
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("symbol", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun abil(characterName: String): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)
        
        return try {
            var ocid = fetchOcid(characterName)
            
            if (isErrorResponse(ocid)) {
                return ResponseDto(formatErrorMessage(ocid))
            }
            
            var abilityResult = getAbility(characterName, ocid).block() ?: "API 오류 발생"
            if (abilityResult.startsWith("닉네임을 다시 확인해주세요")) {
                ocid = fetchOcid(characterName, forceRefresh = true)
                if (!isErrorResponse(ocid)) {
                    abilityResult = getAbility(characterName, ocid).block() ?: "API 오류 발생"
                }
            }
            
            val result = ResponseDto(abilityResult)
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("abil", parameters, result, processingTime)
            
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("abil", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun levelUp(
        characterName: String,
        targetLevel: Int?
    ): ResponseDto {
        val startTime = System.currentTimeMillis()
        var actualTargetLevel = targetLevel
        val parameters: Map<String, Any?> = mapOf("characterName" to characterName, "targetLevel" to targetLevel)
        return try {
            var ocid = fetchOcid(characterName)
            if (isErrorResponse(ocid)) {
                return ResponseDto(formatErrorMessage(ocid))
            }

            val lastWeekDates = getLastWeekDates()
            var levelExpHistory = getOrFetchLevelExpHistory(characterName, ocid, lastWeekDates)
            if (levelExpHistory.isEmpty()) {
                ocid = fetchOcid(characterName, forceRefresh = true)
                if (!isErrorResponse(ocid)) {
                    levelExpHistory = getOrFetchLevelExpHistory(characterName, ocid, lastWeekDates)
                }
            }

            if (levelExpHistory.isEmpty()) {
                return ResponseDto("최근 경험치 데이터가 없습니다.")
            }

            val (curLevel, curExp) = levelExpHistory.last()
            val avgExpGain = ExperienceUtil.calculateAverageExpGain(levelExpHistory)

            val lastDate = lastWeekDates.last()
            val charWorldInfo = getHistory(ocid, lastDate, characterName).block()?.getOrNull(0) ?: ""

            if (actualTargetLevel == null) {
                if (curLevel >= 300) {
                    return ResponseDto("$charWorldInfo\n이미 만렙(300)입니다.")
                }
                actualTargetLevel = curLevel + 1
            }

            if (curLevel >= 300) {
                return ResponseDto("$charWorldInfo\n이미 만렙(300)입니다.")
            }
            if (actualTargetLevel > 300) {
                return ResponseDto("목표 레벨이 300을 초과합니다.")
            }
            if (actualTargetLevel <= curLevel) {
                return ResponseDto("목표 레벨이 현재 레벨보다 낮거나 같습니다.")
            }
            if (avgExpGain <= 0) {
                return ResponseDto("$charWorldInfo\n경험치 증가량이 없어 예상 날짜를 계산할 수 없습니다.")
            }

            val requiredExp = ExperienceUtil.getExpToTargetLevel(curLevel, curExp, actualTargetLevel)
            val estimatedDays = ceil(requiredExp.toDouble() / avgExpGain).toLong()
            val targetDate = LocalDate.now().plusDays(estimatedDays)
            val formattedDate = targetDate.format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"))
            val dateMsg = formattedDate
            val daysMsg = "(${estimatedDays}일 후)"

            val message = buildString {
                if (charWorldInfo.isNotBlank()) {
                    append("$charWorldInfo\n")
                }
                append("현재 레벨 : $curLevel\n")
                append("목표 레벨 : $actualTargetLevel\n")
                append("예상 달성 날짜 : $dateMsg$daysMsg")
            }

            val result = ResponseDto(message)
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("levelUp", parameters, result, processingTime)
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("levelUp", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun ring(characterName: String): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)

        return try {
            var ocid = fetchOcid(characterName)

            if (isErrorResponse(ocid)) {
                return ResponseDto(formatErrorMessage(ocid))
            }

            var ringResult = getRing(characterName, ocid).block() ?: "API 오류 발생"
            if (ringResult.startsWith("닉네임을 다시 확인해주세요")) {
                ocid = fetchOcid(characterName, forceRefresh = true)
                if (!isErrorResponse(ocid)) {
                    ringResult = getRing(characterName, ocid).block() ?: "API 오류 발생"
                }
            }

            val result = ResponseDto(ringResult)
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("ring", parameters, result, processingTime)

            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("ring", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun encryptKey(apiKey: String): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = emptyMap<String, Any>()

        return try {
            val encrypted = CryptoUtil.encrypt(apiKey.trim())
            val result = ResponseDto(encrypted)
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("encryptKey", parameters, ResponseDto("********"), processingTime)

            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("encryptKey", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun boss(characterName: String, apiKey: String?): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)

        return try {
            if (apiKey.isNullOrBlank()) {
                return ResponseDto("/키등록 명령어를 통해 NEXON Open API에서 발급받은 본인의 API_KEY를 등록해주세요.\n사용법: 키등록 [API_KEY]")
            }

            val decryptedApiKey = CryptoUtil.decrypt(apiKey)
                ?: return ResponseDto("유효하지 않은 API Key입니다. /키등록 명령어를 통해 다시 등록해주세요.")

            var ocid = fetchOcid(characterName)

            if (isErrorResponse(ocid)) {
                return ResponseDto(formatErrorMessage(ocid))
            }

            var bossResult = getScheduler(characterName, ocid, decryptedApiKey, ::formatBossInfo).block() ?: "API 오류 발생"
            if (bossResult.startsWith("닉네임을 다시 확인해주세요")) {
                ocid = fetchOcid(characterName, forceRefresh = true)
                if (!isErrorResponse(ocid)) {
                    bossResult = getScheduler(characterName, ocid, decryptedApiKey, ::formatBossInfo).block() ?: "API 오류 발생"
                }
            }

            val result = ResponseDto(bossResult)
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("boss", parameters, result, processingTime)

            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("boss", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun quest(characterName: String, apiKey: String?): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)

        return try {
            if (apiKey.isNullOrBlank()) {
                return ResponseDto("/키등록 명령어를 통해 NEXON Open API에서 발급받은 본인의 API_KEY를 등록해주세요.\n사용법: 키등록 [API_KEY]")
            }

            val decryptedApiKey = CryptoUtil.decrypt(apiKey)
                ?: return ResponseDto("유효하지 않은 API Key입니다. /키등록 명령어를 통해 다시 등록해주세요.")

            var ocid = fetchOcid(characterName)

            if (isErrorResponse(ocid)) {
                return ResponseDto(formatErrorMessage(ocid))
            }

            var questResult = getScheduler(characterName, ocid, decryptedApiKey, ::formatQuestInfo).block() ?: "API 오류 발생"
            if (questResult.startsWith("닉네임을 다시 확인해주세요")) {
                ocid = fetchOcid(characterName, forceRefresh = true)
                if (!isErrorResponse(ocid)) {
                    questResult = getScheduler(characterName, ocid, decryptedApiKey, ::formatQuestInfo).block() ?: "API 오류 발생"
                }
            }

            val result = ResponseDto(questResult)
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("quest", parameters, result, processingTime)

            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("quest", parameters, null, processingTime, e.message)
            throw e
        }
    }

    private fun invalidateOcidIfNeeded(characterName: String, errorName: String?) {
        if (errorName == "OPENAPI00003") {
            try {
                redisService.deleteOcid(characterName)
            } catch (e: Exception) {
                println("Redis OCID 삭제 실패: ${e.message}")
            }
        }
    }

    private fun fetchOcid(characterName: String, forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            val cachedOcid = try {
                redisService.getOcid(characterName)
            } catch (e: Exception) {
                null
            }

            if (cachedOcid != null) {
                return cachedOcid
            }
        } else {
            try {
                redisService.deleteOcid(characterName)
            } catch (e: Exception) {
                println("Redis OCID 삭제 실패: ${e.message}")
            }
        }

        return try {
            webClient.get()
                .uri("/id?character_name=$characterName")
                .exchangeToMono { response ->
                    when (response.statusCode().value()) {
                        200 -> {
                            response.bodyToMono(CharacterDto::class.java)
                                .map { dto ->
                                    redisService.saveOcid(characterName, dto.ocid)
                                    dto.ocid
                                }
                        }
                        400 -> Mono.just("닉네임을 다시 확인해주세요.")
                        403 -> Mono.just("API 오류 발생")
                        429 -> Mono.just("사용량이 많습니다. 다시 시도해주세요.")
                        500 -> Mono.just("Nexon API 서버 오류 발생")
                        else -> {
                            response.bodyToMono(ErrorMessageDto::class.java)
                                .flatMap { errorBody ->
                                    Mono.just("API 오류 발생")
                                }
                        }
                    }
                }
                .onErrorResume { ex ->
                    Mono.just("API 오류 발생")
                }
                .block() ?: "API 오류 발생"
        } catch (e: Exception) {
            "API 오류 발생"
        }
    }

    fun getInfo(ocid: String, characterName: String? = null): Mono<String> {
        return webClient.get()
            .uri("/character/basic?ocid=$ocid")
            .exchangeToMono { response ->
                when (response.statusCode().value()) {
                    200 -> {
                        response.bodyToMono(CharacterBasicDto::class.java)
                            .map { it ->
                                """
                                ${it.character_name}
                                ${it.world_name}${if (it.character_guild_name != null) "@${it.character_guild_name}" else ""}
                                ${it.character_class} | Lv.${it.character_level}
                                
                                """.trimIndent()
                            }
                    }
                    400 -> response.bodyToMono(ErrorMessageDto::class.java)
                        .map { error ->
                            when (error.error.name) {
                                "OPENAPI00010" -> "정보 - 서버 점검 중에는 이용 불가합니다."
                                "OPENAPI00003" -> {
                                    // OCID 무효화 처리
                                    if (!characterName.isNullOrBlank()) {
                                        invalidateOcidIfNeeded(characterName, error.error.name)
                                    }
                                    "닉네임을 다시 확인해주세요"
                                }
                                else -> "2023년 12월 21일 이후의 접속 기록이 없습니다."
                            }
                        }
                    403 -> Mono.just("API 오류 발생")
                    429 -> Mono.just("사용량이 많습니다. 다시 시도해주세요.")
                    500 -> Mono.just("Nexon API 서버 오류 발생")
                    else -> {
                        response.bodyToMono(ErrorMessageDto::class.java)
                            .flatMap { errorBody ->
                                Mono.just("API 오류 발생")
                            }
                    }
                }
            }
            .onErrorResume { ex ->
                Mono.just("API 오류 발생")
            }
    }

    fun getStat(ocid: String, characterName: String? = null): Mono<String> {
        return webClient.get()
            .uri("/character/stat?ocid=$ocid")
            .exchangeToMono { response ->
                when (response.statusCode().value()) {
                    200 -> {
                        response.bodyToMono(CharacterStatDto::class.java)
                            .map { dto ->
                                val stat = dto.final_stat
                                    .find { it.stat_name == "전투력" }?.stat_value?.toDoubleOrNull()

                                if (stat != null) {
                                    val hundredMillion = stat.toLong() / 100_000_000 // 억 단위
                                    val tenThousand = (stat.toLong() % 100_000_000) / 10_000 // 만 단위
                                    val remainder = stat.toLong() % 10_000 // 나머지 숫자

                                    val result = buildString {
                                        if (hundredMillion > 0) append("${hundredMillion}억 ")
                                        if (tenThousand > 0) append("${tenThousand}만 ")
                                        if (remainder > 0 || (hundredMillion == 0L && tenThousand == 0L)) append("$remainder")
                                    }

                                    "전투력: $result"
                                } else {
                                    "정보 없음"
                                }
                            }
                    }
                    400 -> response.bodyToMono(ErrorMessageDto::class.java)
                        .map { error ->
                            when (error.error.name) {
                                "OPENAPI00003" -> {
                                    // OCID 무효화 처리
                                    if (!characterName.isNullOrBlank()) {
                                        invalidateOcidIfNeeded(characterName, error.error.name)
                                    }
                                    "닉네임을 다시 확인해주세요"
                                }
                                else -> "2023년 12월 21일 이후의 접속 기록이 없습니다."
                            }
                        }
                    403 -> Mono.just("API 오류 발생")
                    429 -> Mono.just("사용량이 많습니다. 다시 시도해주세요.")
                    500 -> Mono.just("Nexon API 서버 오류 발생")
                    else -> {
                        response.bodyToMono(ErrorMessageDto::class.java)
                            .flatMap { errorBody ->
                                Mono.just("API 오류 발생")
                            }
                    }
                }
            }
            .onErrorResume { ex ->
                println(ex)
                Mono.just("API 오류 발생")
            }
    }

    fun getDate(beforeDay: Long = 0): String {
        val now = LocalDateTime.now()
        val date = now.minusDays(beforeDay)

        return String.format("%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)
    }

    fun getLastWeekDates(): List<String> {
        val now = LocalDateTime.now()
        val startDay = if (now.hour < 6) 1L else 0L

        return ((startDay + 6) downTo startDay).map { getDate(it) }
    }

    fun getHistory(ocid: String, date: String, characterName: String? = null): Mono<List<String>> {
        val now = LocalDateTime.now()
        val today = getDate(0)

        val isOneDayBehind = LocalDate.parse(date).plusDays(1).toString() == today && now.hour < 6
        val url = if (date == today || isOneDayBehind)
            "/character/basic?ocid=$ocid"
        else
            "/character/basic?ocid=$ocid&date=$date"

        return webClient.get()
            .uri(url)
            .exchangeToMono { response ->
                if (response.statusCode().is2xxSuccessful) {
                    response.bodyToMono(CharacterBasicDto::class.java)
                        .flatMap { dto ->
                            // 필수 필드 검증: null 체크 추가
                            if (dto.character_name.isNullOrEmpty()
                                || dto.world_name.isNullOrEmpty()
                                || dto.character_level == null
                                || dto.character_exp_rate == null
                                || dto.character_exp == null
                            ) {
                                Mono.just(emptyList()) // 유효하지 않은 데이터 → 빈 리스트 반환
                            } else {
                                val characterInfo = "${dto.character_name} - ${dto.world_name}"
                                val formattedDate = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MM월 dd일"))
                                val levelInfo = "Lv.${dto.character_level} ${dto.character_exp_rate}%"
                                val level = dto.character_level.toInt()
                                val exp = dto.character_exp.toLong()

                                // 캐싱 조건 검사 (오늘 데이터 제외)
                                val isSpecialCase =
                                    (now.hour < 6 && LocalDate.parse(date).plusDays(1).toString() == today) ||
                                            (now.hour >= 6 && date == today)
                                if (!isSpecialCase) {
                                    // 기존 방식: 퍼센테이지 저장
                                    redisService.saveHistory(dto.character_name, date, "$formattedDate : $levelInfo")
                                    // 신규 방식: 레벨/실제경험치 저장 (character_level, character_exp)
                                    redisService.saveLevelExp(dto.character_name, date, level, exp)
                                }

                                Mono.just(listOf(characterInfo, "$formattedDate : $levelInfo",  level.toString(), exp.toString()))
                            }
                        }
                } else {
                    response.bodyToMono(ErrorMessageDto::class.java)
                        .flatMap { error ->
                            when (error.error.name) {
                                "OPENAPI00010" -> Mono.just(listOf("정보 - 서버 점검 중에는 이용 불가합니다."))
                                "OPENAPI00003" -> {
                                    // OCID 무효화 처리
                                    if (!characterName.isNullOrBlank()) {
                                        invalidateOcidIfNeeded(characterName, error.error.name)
                                    }
                                    Mono.just(listOf("닉네임을 다시 확인해주세요"))
                                }
                                else -> Mono.just(listOf("2023년 12월 21일 이후의 접속 기록이 없습니다."))
                            }
                        }
                }
            }
            .onErrorResume {
                Mono.just(listOf("API 오류 발생"))
            }
    }

    private fun getSymbol(characterName: String, ocid: String): Mono<String> {
        return webClient.get()
            .uri("/character/symbol-equipment?ocid=$ocid")
            .exchangeToMono { response ->
                when (response.statusCode().value()) {
                    200 -> {
                        response.bodyToMono(CharacterSymbolEquipmentDto::class.java)
                            .map { dto ->
                                if (dto.symbol.isEmpty()) {
                                    "심볼 정보가 없습니다."
                                } else {
                                    formatSymbolInfo(characterName, dto.symbol)
                                }
                            }
                    }
                    400 -> response.bodyToMono(ErrorMessageDto::class.java)
                        .map { error ->
                            when (error.error.name) {
                                "OPENAPI00003" -> {
                                    // OCID 무효화 처리
                                    invalidateOcidIfNeeded(characterName, error.error.name)
                                    "닉네임을 다시 확인해주세요"
                                }
                                else -> "2023년 12월 21일 이후의 접속 기록이 없습니다."
                            }
                        }
                    403 -> Mono.just("API 오류 발생")
                    429 -> Mono.just("사용량이 많습니다. 다시 시도해주세요.")
                    500 -> Mono.just("Nexon API 서버 오류 발생")
                    else -> {
                        response.bodyToMono(ErrorMessageDto::class.java)
                            .flatMap { errorBody ->
                                Mono.just("API 오류 발생")
                            }
                    }
                }
            }
            .onErrorResume { ex ->
                Mono.just("API 오류 발생")
            }
    }

    private fun formatSymbolInfo(characterName: String, symbols: List<SymbolDto>): String {
        // 심볼 이름과 축약어 매핑
        val symbolMapping = mapOf(
            "아케인심볼 : 소멸의 여로" to "여로",
            "아케인심볼 : 츄츄 아일랜드" to "츄츄",
            "아케인심볼 : 레헬른" to "레헬른",
            "아케인심볼 : 아르카나" to "아르카나",
            "아케인심볼 : 모라스" to "모라스",
            "아케인심볼 : 에스페라" to "에스페라",
            "어센틱심볼 : 세르니움" to "세르니움",
            "어센틱심볼 : 아르크스" to "아르크스",
            "어센틱심볼 : 오디움" to "오디움",
            "어센틱심볼 : 도원경" to "도원경",
            "어센틱심볼 : 아르테리아" to "아르테리아",
            "어센틱심볼 : 카르시온" to "카르시온",
            "그랜드 어센틱심볼 : 탈라하트" to "탈라하트"
        )

        // 한 번의 순회로 모든 심볼 레벨 추출
        val symbolLevels = mutableMapOf<String, Int>()

        // 모든 심볼을 기본값 0으로 초기화
        symbolMapping.values.forEach { shortName ->
            symbolLevels[shortName] = 0
        }

        // 한 번의 순회로 착용한 심볼 레벨 설정
        symbols.forEach { symbol ->
            symbolMapping.forEach { (keyword, shortName) ->
                if (symbol.symbol_name.contains(keyword)) {
                    symbolLevels[shortName] = symbol.symbol_level.toInt()
                }
            }
        }

        return buildString {
            append("$characterName - 심볼 정보\n")

            append("* 아케인심볼\n")
            append("여로 Lv.${symbolLevels["여로"]} / 츄츄 Lv.${symbolLevels["츄츄"]}\n")
            append("레헬른 Lv.${symbolLevels["레헬른"]} / 아르카나 Lv.${symbolLevels["아르카나"]}\n")
            append("모라스 Lv.${symbolLevels["모라스"]} / 에스페라 Lv.${symbolLevels["에스페라"]}\n")

            append("* 어센틱심볼\n")
            append("세르니움 Lv.${symbolLevels["세르니움"]} / 아르크스 Lv.${symbolLevels["아르크스"]}\n")
            append("오디움 Lv.${symbolLevels["오디움"]} / 도원경 Lv.${symbolLevels["도원경"]}\n")
            append("아르테리아 Lv.${symbolLevels["아르테리아"]} / 카르시온 Lv.${symbolLevels["카르시온"]}\n")

            append("* 그랜드 어센틱심볼\n")
            append("탈라하트 Lv.${symbolLevels["탈라하트"]}")
        }
    }

    private fun getAbility(characterName: String, ocid: String): Mono<String> {
        return webClient.get()
            .uri("/character/ability?ocid=$ocid")
            .exchangeToMono { response ->
                when (response.statusCode().value()) {
                    200 -> {
                        response.bodyToMono(CharacterAbilityDto::class.java)
                            .map { dto ->
                                formatAbilityInfo(characterName, dto)
                            }
                    }
                    400 -> response.bodyToMono(ErrorMessageDto::class.java)
                        .map { error ->
                            when (error.error.name) {
                                "OPENAPI00003" -> {
                                    // OCID 무효화 처리
                                    invalidateOcidIfNeeded(characterName, error.error.name)
                                    "닉네임을 다시 확인해주세요"
                                }
                                else -> "2023년 12월 21일 이후의 접속 기록이 없습니다."
                            }
                        }
                    403 -> Mono.just("API 오류 발생")
                    429 -> Mono.just("사용량이 많습니다. 다시 시도해주세요.")
                    500 -> Mono.just("Nexon API 서버 오류 발생")
                    else -> {
                        response.bodyToMono(ErrorMessageDto::class.java)
                            .flatMap { errorBody ->
                                Mono.just("API 오류 발생")
                            }
                    }
                }
            }
            .onErrorResume { ex ->
                Mono.just("API 오류 발생")
            }
    }

    private fun formatAbilityInfo(characterName: String, dto: CharacterAbilityDto): String {
        val currentPreset = dto.preset_no.toInt()

        return buildString {
            append("$characterName - 어빌리티 정보\n")

            // 프리셋 1
            val preset1Prefix = if (currentPreset == 1) "* " else ""
            append("${preset1Prefix}프리셋 1[${dto.ability_preset_1.ability_preset_grade}]\n")
            dto.ability_preset_1.ability_info.forEachIndexed { index, abilityInfo ->
                append("${index + 1}. [${abilityInfo.ability_grade}] ${abilityInfo.ability_value}\n")
            }

            // 프리셋 2
            val preset2Prefix = if (currentPreset == 2) "* " else ""
            append("${preset2Prefix}프리셋 2[${dto.ability_preset_2.ability_preset_grade}]\n")
            dto.ability_preset_2.ability_info.forEachIndexed { index, abilityInfo ->
                append("${index + 1}. [${abilityInfo.ability_grade}] ${abilityInfo.ability_value}\n")
            }

            // 프리셋 3
            val preset3Prefix = if (currentPreset == 3) "* " else ""
            append("${preset3Prefix}프리셋 3[${dto.ability_preset_3.ability_preset_grade}]")
            if (dto.ability_preset_3.ability_info.isNotEmpty()) {
                append("\n")
                dto.ability_preset_3.ability_info.forEachIndexed { index, abilityInfo ->
                    append("${index + 1}. [${abilityInfo.ability_grade}] ${abilityInfo.ability_value}")
                    if (index < dto.ability_preset_3.ability_info.size - 1) {
                        append("\n")
                    }
                }
            }
        }
    }

    /**
     * 최근 N일간 (레벨, 경험치) 이력 조회 (redis 우선, 없으면 api 호출 후 redis 저장)
     */
    private fun getOrFetchLevelExpHistory(characterName: String, ocid: String, dates: List<String>): List<Pair<Int, Long>> {
        val result = mutableListOf<Pair<Int, Long>>()
        
        for (date in dates) {
            val redisData = redisService.getLevelExp(characterName, date)
            if (redisData != null) {
                result.add(redisData)
                continue
            }
            // redis에 없으면 api 호출 (오늘/0~6시 특수 케이스는 redis 저장 X)
            val apiData = getHistory(ocid, date, characterName).block()
            if (apiData != null && apiData.size >= 4) {
                val level = apiData[2].toIntOrNull()
                val exp = apiData[3].toLongOrNull()
                if (level != null && exp != null) {
                    // 오늘 날짜 또는 0~6시 특수 케이스는 redis 저장하지 않음
                    val now = LocalDateTime.now()
                    val today = getDate(0)
                    val isSpecialCase =
                        (now.hour < 6 && LocalDate.parse(date).plusDays(1).toString() == today) ||
                        (now.hour >= 6 && date == today)
                    if (!isSpecialCase) {
                        redisService.saveLevelExp(characterName, date, level, exp)
                    }
                    result.add(Pair(level, exp))
                }
            }
        }
        return result
    }

    fun getUnion(ocid: String): Mono<String> {
        return webClient.get()
            .uri("/user/union?ocid=$ocid")
            .exchangeToMono { response ->
                when (response.statusCode().value()) {
                    200 -> {
                        response.bodyToMono(UserUnionDto::class.java)
                            .map { dto ->
                                if (dto.union_level != null && !dto.union_grade.isNullOrBlank()) {
                                    "\n유니온: Lv.${dto.union_level} / ${dto.union_grade}\n아티팩트: Lv.${dto.union_artifact_level ?: 0}"
                                } else {
                                    ""
                                }
                            }
                    }
                    else -> Mono.just("")
                }
            }
            .onErrorResume {
                Mono.just("")
            }
    }

    private fun getRing(characterName: String, ocid: String): Mono<String> {
        return webClient.get()
            .uri("/character/item-equipment?ocid=$ocid")
            .exchangeToMono { response ->
                when (response.statusCode().value()) {
                    200 -> {
                        response.bodyToMono(CharacterItemEquipmentDto::class.java)
                            .map { dto ->
                                val mainRing = dto.item_equipment?.find {
                                    it.item_equipment_part == "반지" &&
                                        it.item_equipment_slot != "예비 특수 반지" &&
                                        (it.special_ring_level?.toInt() ?: 0) in 1..6 &&
                                        !it.item_name.isNullOrBlank()
                                }
                                val reserveRing = dto.item_equipment?.find {
                                    it.item_equipment_slot == "예비 특수 반지" &&
                                        (it.special_ring_level?.toInt() ?: 0) in 1..6 &&
                                        !it.item_name.isNullOrBlank()
                                }

                                val mainRingStr = if (mainRing != null) {
                                    "시드링: ${mainRing.item_name} Lv.${mainRing.special_ring_level}"
                                } else {
                                    "시드링: 미장착"
                                }

                                val reserveRingStr = if (reserveRing != null) {
                                    "보조 시드링: ${reserveRing.item_name} Lv.${reserveRing.special_ring_level}"
                                } else {
                                    "보조 시드링: 미장착"
                                }

                                """
                                $characterName - 시드링 정보
                                $mainRingStr
                                $reserveRingStr
                                """.trimIndent()
                            }
                    }
                    400 -> response.bodyToMono(ErrorMessageDto::class.java)
                        .map { error ->
                            when (error.error.name) {
                                "OPENAPI00010" -> "정보 - 서버 점검 중에는 이용 불가합니다."
                                "OPENAPI00003" -> {
                                    invalidateOcidIfNeeded(characterName, error.error.name)
                                    "닉네임을 다시 확인해주세요"
                                }
                                "OPENAPI00002", "OPENAPI00005" -> "유효하지 않은 API Key입니다. /키등록 명령어를 통해 다시 등록해주세요."
                                else -> "2023년 12월 21일 이후의 접속 기록이 없습니다."
                            }
                        }
                    403 -> Mono.just("유효하지 않은 API Key입니다. /키등록 명령어를 통해 다시 등록해주세요.")
                    429 -> Mono.just("사용량이 많습니다. 다시 시도해주세요.")
                    500 -> Mono.just("Nexon API 서버 오류 발생")
                    else -> {
                        response.bodyToMono(ErrorMessageDto::class.java)
                            .flatMap {
                                Mono.just("API 오류 발생")
                            }
                    }
                }
            }
            .onErrorResume {
                Mono.just("API 오류 발생")
            }
    }

    private fun getScheduler(
        characterName: String,
        ocid: String,
        apiKey: String? = null,
        formatter: (String, CharacterSchedulerDto) -> String
    ): Mono<String> {
        val requestSpec = webClient.get()
            .uri("/scheduler/character-state?ocid=$ocid")

        if (!apiKey.isNullOrBlank()) {
            requestSpec.header("x-nxopen-api-key", apiKey)
        }

        return requestSpec
            .exchangeToMono { response ->
                when (response.statusCode().value()) {
                    200 -> {
                        response.bodyToMono(CharacterSchedulerDto::class.java)
                            .map { dto ->
                                formatter(characterName, dto)
                            }
                    }
                    400 -> response.bodyToMono(ErrorMessageDto::class.java)
                        .map { error ->
                            when (error.error.name) {
                                "OPENAPI00010" -> "정보 - 서버 점검 중에는 이용 불가합니다."
                                "OPENAPI00003" -> {
                                    "닉네임을 다시 확인해주세요"
                                }
                                "OPENAPI00004" -> {
                                    "자신의 계정에 속한 캐릭터만 조회가 가능합니다."
                                }
                                else -> "2023년 12월 21일 이후의 접속 기록이 없습니다."
                            }
                        }
                    403 -> Mono.just("API 오류 발생")
                    429 -> Mono.just("사용량이 많습니다. 다시 시도해주세요.")
                    500 -> Mono.just("Nexon API 서버 오류 발생")
                    else -> {
                        response.bodyToMono(ErrorMessageDto::class.java)
                            .flatMap {
                                Mono.just("API 오류 발생")
                            }
                    }
                }
            }
            .onErrorResume {
                Mono.just("API 오류 발생")
            }
    }

    private fun formatBossInfo(characterName: String, dto: CharacterSchedulerDto): String {
        val difficultyMap = mapOf(
            "easy" to "이지",
            "normal" to "노멀",
            "hard" to "하드",
            "chaos" to "카오스",
            "extreme" to "익스트림"
        )

        val weeklyBosses = dto.boss_contents.orEmpty()
            .filter { it.registration_flag == "true" && it.cycle == "bossWeekly" }
            .sortedBy { it.list_order_no?.toInt() ?: Int.MAX_VALUE }

        val monthlyBosses = dto.boss_contents.orEmpty()
            .filter { it.registration_flag == "true" && it.cycle == "bossMonthly" }
            .sortedBy { it.list_order_no?.toInt() ?: Int.MAX_VALUE }

        val clearCount = dto.weekly_boss_clear_count?.toInt() ?: 0
        val rawLimitCount = dto.weekly_boss_clear_limit_count?.toInt() ?: 12
        val limitCount = if (rawLimitCount > 0) rawLimitCount else 12

        return buildString {
            append("$characterName - 보스 정보\n")

            append("[주간 보스] ($clearCount/$limitCount)\n")
            if (weeklyBosses.isEmpty()) {
                append("- 등록된 항목 없음\n")
            } else {
                weeklyBosses.forEach { boss ->
                    val korDifficulty = difficultyMap[boss.difficulty?.lowercase()] ?: (boss.difficulty ?: "")
                    val status = if (boss.complete_flag == "true") "완료" else "미완료"
                    append("- ${boss.content_name}($korDifficulty): $status\n")
                }
            }

            append("[월간 보스]\n")
            if (monthlyBosses.isEmpty()) {
                append("- 등록된 항목 없음")
            } else {
                monthlyBosses.forEachIndexed { index, boss ->
                    val korDifficulty = difficultyMap[boss.difficulty?.lowercase()] ?: (boss.difficulty ?: "")
                    val status = if (boss.complete_flag == "true") "완료" else "미완료"
                    append("- ${boss.content_name}($korDifficulty): $status")
                    if (index < monthlyBosses.size - 1) {
                        append("\n")
                    }
                }
            }
        }
    }

    private fun formatQuestInfo(characterName: String, dto: CharacterSchedulerDto): String {
        val dailyQuests = dto.daily_contents.orEmpty().filter {
            it.type == "quest" && (it.registration_flag == "true" || (!it.quest_state.isNullOrBlank() && it.quest_state != "0"))
        }

        val weeklyQuests = dto.weekly_contents.orEmpty().filter {
            it.type == "quest" && (it.registration_flag == "true" || (!it.quest_state.isNullOrBlank() && it.quest_state != "0"))
        }

        val dailyAccountContents = dto.daily_contents.orEmpty().filter {
            it.registration_flag == "true" && it.type == "contents"
        }
        val weeklyAccountContents = dto.weekly_contents.orEmpty().filter {
            it.registration_flag == "true" && it.type == "contents"
        }
        val accountContents = dailyAccountContents + weeklyAccountContents

        return buildString {
            append("$characterName - 일퀘 정보\n")

            append("[일일 퀘스트]\n")
            if (dailyQuests.isEmpty()) {
                append("- 등록된 항목 없음\n")
            } else {
                dailyQuests.forEach { quest ->
                    val status = if (quest.quest_state == "2") "완료" else "미완료"
                    append("- ${quest.content_name}: $status\n")
                }
            }

            append("[주간 퀘스트]\n")
            if (weeklyQuests.isEmpty()) {
                append("- 등록된 항목 없음\n")
            } else {
                weeklyQuests.forEach { quest ->
                    val status = if (quest.quest_state == "2") "완료" else "미완료"
                    append("- ${quest.content_name}: $status\n")
                }
            }

            append("[계정 콘텐츠]\n")
            if (accountContents.isEmpty()) {
                append("- 등록된 항목 없음")
            } else {
                accountContents.forEachIndexed { index, content ->
                    val now = content.now_count?.toInt() ?: 0
                    val max = content.max_count?.toInt() ?: 0
                    val status = when {
                        content.type == "quest" -> if (content.quest_state == "2") "완료" else "미완료"
                        max > 1 -> "$now/$max"
                        else -> if (now > 0) "완료" else "미완료"
                    }
                    append("- ${content.content_name}: $status")
                    if (index < accountContents.size - 1) {
                        append("\n")
                    }
                }
            }
        }
    }
}