package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.model.*
import com.mukho.ssammubot.service.ApiLogService
import com.mukho.ssammubot.service.NexonService
import com.mukho.ssammubot.service.RedisService
import com.mukho.ssammubot.service.impl.nexon.NexonErrorPolicy
import com.mukho.ssammubot.service.impl.nexon.NexonMessage
import com.mukho.ssammubot.utils.CryptoUtil
import com.mukho.ssammubot.utils.ExperienceUtil
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

@Service("nexonService")
class NexonServiceImpl(
    private val webClient: WebClient,
    private val redisService: RedisService,
    private val apiLogService: ApiLogService
): NexonService {

    companion object {
        private val TARGET_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일")
        private val HISTORY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM월 dd일")

        private val SYMBOL_MAPPING: Map<String, String> = mapOf(
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
            "그랜드 어센틱심볼 : 탈라하트" to "탈라하트",
            "그랜드 어센틱심볼 : 기어드락" to "기어드락"
        )

        private val BOSS_DIFFICULTY_MAP: Map<String, String> = mapOf(
            "easy" to "이지",
            "normal" to "노멀",
            "hard" to "하드",
            "chaos" to "카오스",
            "extreme" to "익스트림"
        )
    }

    override fun scouter(characterName: String): ResponseDto =
        logged("scouter", mapOf("characterName" to characterName)) {
            val ocid = fetchOcid(characterName)
            if (NexonMessage.isError(ocid)) {
                return@logged ResponseDto(ocid)
            }
            ResponseDto("https://maplescouter.com/info?name=$characterName")
        }

    override fun info(characterName: String): ResponseDto =
        logged("info", mapOf("characterName" to characterName)) {
            ResponseDto(withOcidRetry(characterName) { ocid -> fetchInfoResult(ocid, characterName) })
        }

    private fun fetchInfoResult(ocid: String, characterName: String): String {
        return try {
            Mono.zip(getInfo(ocid, characterName), getStat(ocid, characterName), getUnion(ocid))
                .map { tuple ->
                    val infoResult = tuple.t1
                    val statResult = tuple.t2
                    val unionResult = tuple.t3
                    val results = listOf(infoResult, statResult)

                    results.firstOrNull { it == NexonMessage.CHECK_NICKNAME }
                        ?: results.firstOrNull { NexonMessage.isError(it) }
                        ?: (infoResult + statResult + unionResult)
                }
                .blockOrError()
        } catch (e: Exception) {
            NexonMessage.API_ERROR
        }
    }

    override fun history(characterName: String): ResponseDto =
        logged("history", mapOf("characterName" to characterName)) {
            ResponseDto(withOcidRetry(characterName) { ocid -> fetchHistoryResult(ocid, characterName) })
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
                            val errorMsg = characterBasic[0]
                            if (errorMsg == NexonMessage.NO_RECORD) {
                                isValidId = false
                                break
                            }
                            return errorMsg
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
                    val formattedDate = targetDate.format(TARGET_DATE_FORMATTER)
                    levelUpEstimateMsg = "예상 레벨업 날짜 : $formattedDate(${days}일 후)"
                }
            }

            buildString {
                if (expData.isEmpty()) {
                    if (isValidId) {
                        append(NexonMessage.NO_HISTORY)
                    } else {
                        append(NexonMessage.NO_RECORD)
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
            NexonMessage.API_ERROR
        }
    }

    override fun symbol(characterName: String): ResponseDto =
        logged("symbol", mapOf("characterName" to characterName)) {
            ResponseDto(withOcidRetry(characterName) { ocid -> getSymbol(characterName, ocid).blockOrError() })
        }

    override fun abil(characterName: String): ResponseDto =
        logged("abil", mapOf("characterName" to characterName)) {
            ResponseDto(withOcidRetry(characterName) { ocid -> getAbility(characterName, ocid).blockOrError() })
        }

    override fun levelUp(
        characterName: String,
        targetLevel: Int?
    ): ResponseDto = logged("levelUp", mapOf("characterName" to characterName, "targetLevel" to targetLevel)) {
        val lastWeekDates = getLastWeekDates()

        val (ocid, levelExpHistory) = withOcidRetry(
            characterName = characterName,
            shouldRetry = { (_, history) -> history.isEmpty() },
            onOcidError = { err -> return@logged ResponseDto(err) },
        ) { resolvedOcid ->
            resolvedOcid to getOrFetchLevelExpHistory(characterName, resolvedOcid, lastWeekDates)
        }

        if (levelExpHistory.isEmpty()) {
            return@logged ResponseDto(NexonMessage.NO_RECENT_EXP)
        }

        val (curLevel, curExp) = levelExpHistory.last()
        val avgExpGain = ExperienceUtil.calculateAverageExpGain(levelExpHistory)

        val lastDate = lastWeekDates.last()
        val charWorldInfo = getHistory(ocid, lastDate, characterName).block()?.getOrNull(0) ?: ""

        if (curLevel >= 300) {
            return@logged ResponseDto("$charWorldInfo\n이미 만렙(300)입니다.")
        }

        val actualTargetLevel = targetLevel ?: (curLevel + 1)

        if (actualTargetLevel > 300) {
            return@logged ResponseDto("목표 레벨이 300을 초과합니다.")
        }
        if (actualTargetLevel <= curLevel) {
            return@logged ResponseDto("목표 레벨이 현재 레벨보다 낮거나 같습니다.")
        }
        if (avgExpGain <= 0) {
            return@logged ResponseDto("$charWorldInfo\n경험치 증가량이 없어 예상 날짜를 계산할 수 없습니다.")
        }

        val requiredExp = ExperienceUtil.getExpToTargetLevel(curLevel, curExp, actualTargetLevel)
        val estimatedDays = ceil(requiredExp.toDouble() / avgExpGain).toLong()
        val targetDate = LocalDate.now().plusDays(estimatedDays)
        val formattedDate = targetDate.format(TARGET_DATE_FORMATTER)

        val message = buildString {
            if (charWorldInfo.isNotBlank()) {
                append("$charWorldInfo\n")
            }
            append("현재 레벨 : $curLevel\n")
            append("목표 레벨 : $actualTargetLevel\n")
            append("예상 달성 날짜 : $formattedDate(${estimatedDays}일 후)")
        }

        ResponseDto(message)
    }

    override fun ring(characterName: String): ResponseDto =
        logged("ring", mapOf("characterName" to characterName)) {
            ResponseDto(withOcidRetry(characterName) { ocid -> getRing(characterName, ocid).blockOrError() })
        }

    override fun encryptKey(apiKey: String): ResponseDto =
        logged("encryptKey", emptyMap(), maskResult = true) {
            ResponseDto(CryptoUtil.encrypt(apiKey.trim()))
        }

    override fun boss(characterName: String, apiKey: String?): ResponseDto =
        logged("boss", mapOf("characterName" to characterName)) {
            val decryptedApiKey = decryptApiKeyOrError(apiKey)
                .getOrElse { return@logged ResponseDto(it.message ?: NexonMessage.INVALID_API_KEY) }

            ResponseDto(withOcidRetry(characterName) { ocid ->
                getScheduler(characterName, ocid, decryptedApiKey, ::formatBossInfo).blockOrError()
            })
        }

    override fun quest(characterName: String, apiKey: String?): ResponseDto =
        logged("quest", mapOf("characterName" to characterName)) {
            val decryptedApiKey = decryptApiKeyOrError(apiKey)
                .getOrElse { return@logged ResponseDto(it.message ?: NexonMessage.INVALID_API_KEY) }

            ResponseDto(withOcidRetry(characterName) { ocid ->
                getScheduler(characterName, ocid, decryptedApiKey, ::formatQuestInfo).blockOrError()
            })
        }

    private inline fun logged(
        apiName: String,
        parameters: Map<String, Any?>,
        maskResult: Boolean = false,
        block: () -> ResponseDto,
    ): ResponseDto {
        val startTime = System.currentTimeMillis()
        return try {
            block().also { result ->
                val loggedResult = if (maskResult) ResponseDto("********") else result
                val processingTime = System.currentTimeMillis() - startTime
                apiLogService.logApiCall(apiName, parameters, loggedResult, processingTime)
            }
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall(apiName, parameters, null, processingTime, e.message)
            throw e
        }
    }

    private inline fun <T> withOcidRetry(
        characterName: String,
        shouldRetry: (T) -> Boolean,
        onOcidError: (String) -> T,
        fetch: (ocid: String) -> T,
    ): T {
        val ocid = fetchOcid(characterName)
        if (NexonMessage.isError(ocid)) {
            return onOcidError(ocid)
        }

        val first = fetch(ocid)
        if (!shouldRetry(first)) {
            return first
        }

        val refreshed = fetchOcid(characterName, forceRefresh = true)
        return if (NexonMessage.isError(refreshed)) first else fetch(refreshed)
    }

    private fun withOcidRetry(characterName: String, fetch: (ocid: String) -> String): String =
        withOcidRetry(
            characterName = characterName,
            shouldRetry = { it == NexonMessage.CHECK_NICKNAME },
            onOcidError = { it },
            fetch = fetch,
        )

    private fun decryptApiKeyOrError(apiKey: String?): Result<String> {
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException(NexonMessage.API_KEY_REQUIRED))
        }
        val decrypted = CryptoUtil.decrypt(apiKey)
            ?: return Result.failure(IllegalArgumentException(NexonMessage.INVALID_API_KEY))
        return Result.success(decrypted)
    }

    private fun Mono<String>.blockOrError(): String = block() ?: NexonMessage.API_ERROR

    private fun <T : Any, R : Any> nexonGet(
        uri: String,
        bodyType: Class<T>,
        characterName: String?,
        apiKey: String? = null,
        policy: NexonErrorPolicy = NexonErrorPolicy.DEFAULT,
        wrapError: (String) -> R,
        onSuccess: (T) -> R,
    ): Mono<R> {
        val requestSpec = webClient.get().uri(uri)
        if (!apiKey.isNullOrBlank()) {
            requestSpec.header("x-nxopen-api-key", apiKey)
        }

        return requestSpec
            .exchangeToMono { response ->
                when (val status = response.statusCode().value()) {
                    200 -> response.bodyToMono(bodyType).map(onSuccess)
                    400 -> response.bodyToMono(ErrorMessageDto::class.java)
                        .map { error -> wrapError(mapErrorCode(error.error.name, characterName, policy)) }
                        .defaultIfEmpty(wrapError(policy.fallback400))
                    else -> response.releaseBody()
                        .thenReturn(wrapError(mapHttpStatus(status, policy)))
                }
            }
            .onErrorResume {
                Mono.just(wrapError(NexonMessage.API_ERROR))
            }
    }

    private fun <T : Any> nexonGet(
        uri: String,
        bodyType: Class<T>,
        characterName: String?,
        apiKey: String? = null,
        policy: NexonErrorPolicy = NexonErrorPolicy.DEFAULT,
        onSuccess: (T) -> String,
    ): Mono<String> = nexonGet(
        uri = uri,
        bodyType = bodyType,
        characterName = characterName,
        apiKey = apiKey,
        policy = policy,
        wrapError = { it },
        onSuccess = onSuccess,
    )

    private fun mapErrorCode(code: String, characterName: String?, policy: NexonErrorPolicy): String {
        policy.codeMessages[code]?.let { return it }
        return when (code) {
            "OPENAPI00010" -> NexonMessage.MAINTENANCE
            "OPENAPI00009" -> NexonMessage.DATA_PREPARING
            "OPENAPI00003" -> {
                if (!characterName.isNullOrBlank()) {
                    invalidateOcid(characterName)
                }
                NexonMessage.CHECK_NICKNAME
            }
            else -> policy.fallback400
        }
    }

    private fun mapHttpStatus(status: Int, policy: NexonErrorPolicy): String = when (status) {
        403 -> policy.forbiddenMessage
        429 -> NexonMessage.TOO_MANY_REQUESTS
        500 -> NexonMessage.SERVER_ERROR
        503 -> NexonMessage.API_MAINTENANCE
        else -> NexonMessage.API_ERROR
    }

    private fun invalidateOcid(characterName: String) {
        try {
            redisService.deleteOcid(characterName)
        } catch (e: Exception) {
            println("Redis OCID 삭제 실패: ${e.message}")
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
            invalidateOcid(characterName)
        }

        return try {
            nexonGet(
                uri = "/id?character_name=$characterName",
                bodyType = CharacterDto::class.java,
                characterName = null,
                policy = NexonErrorPolicy.OCID,
            ) { dto ->
                redisService.saveOcid(characterName, dto.ocid)
                dto.ocid
            }.blockOrError()
        } catch (e: Exception) {
            NexonMessage.API_ERROR
        }
    }

    private fun getInfo(ocid: String, characterName: String? = null): Mono<String> {
        return nexonGet(
            uri = "/character/basic?ocid=$ocid",
            bodyType = CharacterBasicDto::class.java,
            characterName = characterName,
        ) {
            """
            ${it.character_name}
            ${it.world_name}${if (it.character_guild_name != null) "@${it.character_guild_name}" else ""}
            ${it.character_class} | Lv.${it.character_level}
            
            """.trimIndent()
        }
    }

    private fun getStat(ocid: String, characterName: String? = null): Mono<String> {
        return nexonGet(
            uri = "/character/stat?ocid=$ocid",
            bodyType = CharacterStatDto::class.java,
            characterName = characterName,
        ) { dto ->
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

    private fun getDate(beforeDay: Long = 0): String {
        val now = LocalDateTime.now()
        val date = now.minusDays(beforeDay)

        return String.format("%04d-%02d-%02d", date.year, date.monthValue, date.dayOfMonth)
    }

    private fun getLastWeekDates(): List<String> {
        val now = LocalDateTime.now()
        val startDay = if (now.hour < 6) 1L else 0L

        return ((startDay + 6) downTo startDay).map { getDate(it) }
    }

    private fun isCacheable(date: String, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val today = getDate(0)
        val isSpecialCase =
            (now.hour < 6 && LocalDate.parse(date).plusDays(1).toString() == today) ||
                (now.hour >= 6 && date == today)
        return !isSpecialCase
    }

    private fun getHistory(ocid: String, date: String, characterName: String? = null): Mono<List<String>> {
        val now = LocalDateTime.now()
        val cacheable = isCacheable(date, now)
        val url = if (!cacheable)
            "/character/basic?ocid=$ocid"
        else
            "/character/basic?ocid=$ocid&date=$date"

        return nexonGet(
            uri = url,
            bodyType = CharacterBasicDto::class.java,
            characterName = characterName,
            wrapError = { listOf(it) },
        ) { dto ->
            // 필수 필드 검증: null 체크 추가
            if (dto.character_name.isNullOrEmpty()
                || dto.world_name.isNullOrEmpty()
                || dto.character_level == null
                || dto.character_exp_rate == null
                || dto.character_exp == null
            ) {
                emptyList() // 유효하지 않은 데이터 → 빈 리스트 반환
            } else {
                val characterInfo = "${dto.character_name} - ${dto.world_name}"
                val formattedDate = LocalDate.parse(date).format(HISTORY_DATE_FORMATTER)
                val levelInfo = "Lv.${dto.character_level} ${dto.character_exp_rate}%"
                val level = dto.character_level.toInt()
                val exp = dto.character_exp.toLong()

                // 캐싱 조건 검사 (오늘 데이터 제외)
                if (cacheable) {
                    // 기존 방식: 퍼센테이지 저장
                    redisService.saveHistory(dto.character_name, date, "$formattedDate : $levelInfo")
                    // 신규 방식: 레벨/실제경험치 저장 (character_level, character_exp)
                    redisService.saveLevelExp(dto.character_name, date, level, exp)
                }

                listOf(characterInfo, "$formattedDate : $levelInfo", level.toString(), exp.toString())
            }
        }
    }

    private fun getSymbol(characterName: String, ocid: String): Mono<String> {
        return nexonGet(
            uri = "/character/symbol-equipment?ocid=$ocid",
            bodyType = CharacterSymbolEquipmentDto::class.java,
            characterName = characterName,
        ) { dto ->
            if (dto.symbol.isEmpty()) {
                NexonMessage.NO_SYMBOL
            } else {
                formatSymbolInfo(characterName, dto.symbol)
            }
        }
    }

    private fun formatSymbolInfo(characterName: String, symbols: List<SymbolDto>): String {
        // 모든 심볼을 기본값 0으로 초기화
        val symbolLevels = SYMBOL_MAPPING.values.associateWith { 0 }.toMutableMap()

        // 한 번의 순회로 착용한 심볼 레벨 설정
        symbols.forEach { symbol ->
            SYMBOL_MAPPING.forEach { (keyword, shortName) ->
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
        return nexonGet(
            uri = "/character/ability?ocid=$ocid",
            bodyType = CharacterAbilityDto::class.java,
            characterName = characterName,
        ) { dto ->
            formatAbilityInfo(characterName, dto)
        }
    }

    private fun formatAbilityInfo(characterName: String, dto: CharacterAbilityDto): String {
        val currentPreset = dto.preset_no.toInt()
        val presets = listOf(dto.ability_preset_1, dto.ability_preset_2, dto.ability_preset_3)

        val presetBlocks = presets.mapIndexed { idx, preset ->
            val presetNo = idx + 1
            val prefix = if (currentPreset == presetNo) "* " else ""
            val header = "${prefix}프리셋 $presetNo[${preset.ability_preset_grade}]"
            val lines = preset.ability_info.mapIndexed { lineIdx, info ->
                "${lineIdx + 1}. [${info.ability_grade}] ${info.ability_value}"
            }
            (listOf(header) + lines).joinToString("\n")
        }

        return "$characterName - 어빌리티 정보\n" + presetBlocks.joinToString("\n")
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
                    if (isCacheable(date)) {
                        redisService.saveLevelExp(characterName, date, level, exp)
                    }
                    result.add(Pair(level, exp))
                }
            }
        }
        return result
    }

    private fun getUnion(ocid: String): Mono<String> {
        return nexonGet(
            uri = "/user/union?ocid=$ocid",
            bodyType = UserUnionDto::class.java,
            characterName = null,
            wrapError = { "" },
        ) { dto ->
            if (dto.union_level != null && !dto.union_grade.isNullOrBlank()) {
                "\n유니온: Lv.${dto.union_level} / ${dto.union_grade}\n아티팩트: Lv.${dto.union_artifact_level ?: 0}"
            } else {
                ""
            }
        }
    }

    private fun getRing(characterName: String, ocid: String): Mono<String> {
        return nexonGet(
            uri = "/character/item-equipment?ocid=$ocid",
            bodyType = CharacterItemEquipmentDto::class.java,
            characterName = characterName,
        ) { dto ->
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

    private fun getScheduler(
        characterName: String,
        ocid: String,
        apiKey: String? = null,
        formatter: (String, CharacterSchedulerDto) -> String
    ): Mono<String> {
        return nexonGet(
            uri = "/scheduler/character-state?ocid=$ocid",
            bodyType = CharacterSchedulerDto::class.java,
            characterName = characterName,
            apiKey = apiKey,
            policy = NexonErrorPolicy.USER_API_KEY,
        ) { dto ->
            formatter(characterName, dto)
        }
    }

    private fun formatSection(title: String, lines: List<String>): String {
        val body = if (lines.isEmpty()) "- 등록된 항목 없음" else lines.joinToString("\n")
        return "$title\n$body"
    }

    private fun formatBossInfo(characterName: String, dto: CharacterSchedulerDto): String {
        fun formatBossLine(boss: SchedulerBossDto): String {
            val korDifficulty = BOSS_DIFFICULTY_MAP[boss.difficulty?.lowercase()] ?: (boss.difficulty ?: "")
            val status = if (boss.complete_flag == "true") "완료" else "미완료"
            return "- ${boss.content_name}($korDifficulty): $status"
        }

        val weeklyBossLines = dto.boss_contents.orEmpty()
            .filter { it.registration_flag == "true" && it.cycle == "bossWeekly" }
            .sortedBy { it.list_order_no?.toInt() ?: Int.MAX_VALUE }
            .map(::formatBossLine)

        val monthlyBossLines = dto.boss_contents.orEmpty()
            .filter { it.registration_flag == "true" && it.cycle == "bossMonthly" }
            .sortedBy { it.list_order_no?.toInt() ?: Int.MAX_VALUE }
            .map(::formatBossLine)

        val clearCount = dto.weekly_boss_clear_count?.toInt() ?: 0
        val rawLimitCount = dto.weekly_boss_clear_limit_count?.toInt() ?: 12
        val limitCount = if (rawLimitCount > 0) rawLimitCount else 12

        return listOf(
            "$characterName - 보스 정보",
            formatSection("[주간 보스] ($clearCount/$limitCount)", weeklyBossLines),
            formatSection("[월간 보스]", monthlyBossLines),
        ).joinToString("\n")
    }

    private fun formatQuestInfo(characterName: String, dto: CharacterSchedulerDto): String {
        fun isRegisteredOrStartedQuest(content: SchedulerContentDto): Boolean =
            content.type == "quest" &&
                (content.registration_flag == "true" || (!content.quest_state.isNullOrBlank() && content.quest_state != "0"))

        fun formatQuestLine(quest: SchedulerContentDto): String {
            val status = if (quest.quest_state == "2") "완료" else "미완료"
            return "- ${quest.content_name}: $status"
        }

        val dailyQuestLines = dto.daily_contents.orEmpty()
            .filter(::isRegisteredOrStartedQuest)
            .map(::formatQuestLine)

        val weeklyQuestLines = dto.weekly_contents.orEmpty()
            .filter(::isRegisteredOrStartedQuest)
            .map(::formatQuestLine)

        val accountContentLines = (dto.daily_contents.orEmpty() + dto.weekly_contents.orEmpty())
            .filter { it.registration_flag == "true" && it.type == "contents" }
            .map { content ->
                val now = content.now_count?.toInt() ?: 0
                val max = content.max_count?.toInt() ?: 0
                val status = when {
                    content.type == "quest" -> if (content.quest_state == "2") "완료" else "미완료"
                    max > 1 -> "$now/$max"
                    else -> if (now > 0) "완료" else "미완료"
                }
                "- ${content.content_name}: $status"
            }

        return listOf(
            "$characterName - 일퀘 정보",
            formatSection("[일일 퀘스트]", dailyQuestLines),
            formatSection("[주간 퀘스트]", weeklyQuestLines),
            formatSection("[계정 콘텐츠]", accountContentLines),
        ).joinToString("\n")
    }
}
