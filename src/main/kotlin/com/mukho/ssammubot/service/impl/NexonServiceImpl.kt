package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.model.*
import com.mukho.ssammubot.service.ApiLogService
import com.mukho.ssammubot.service.NexonService
import com.mukho.ssammubot.service.RedisService
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

    override fun scouter(characterName: String): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)
        
        return try {
            val ocid = fetchOcid(characterName)
            
            val result = when {
                ocid.startsWith("닉네임을 다시 확인해주세요") -> ResponseDto("닉네임을 다시 확인해주세요.")
                ocid.startsWith("API 오류 발생") -> ResponseDto("API 오류 발생")
                ocid.startsWith("사용량이 많습니다. 다시 시도해주세요.") -> ResponseDto("사용량이 많습니다. 다시 시도해주세요.")
                ocid.startsWith("Nexon API 서버 오류 발생") -> ResponseDto("Nexon API 서버 오류 발생")
                else -> ResponseDto("https://maplescouter.com/info?name=$characterName")
            }
            
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
            val ocid = fetchOcid(characterName)
            
            val result = when {
                ocid.startsWith("닉네임을 다시 확인해주세요") -> ResponseDto("닉네임을 다시 확인해주세요")
                ocid.startsWith("API 오류 발생") -> ResponseDto("API 오류 발생")
                ocid.startsWith("사용량이 많습니다. 다시 시도해주세요.") -> ResponseDto("사용량이 많습니다. 다시 시도해주세요.")
                ocid.startsWith("Nexon API 서버 오류 발생") -> ResponseDto("Nexon API 서버 오류 발생")
                else -> {
                    try {
                        val result = Mono.zip(getInfo(ocid), getStat(ocid))
                            .map { tuple ->
                                val infoResult = tuple.t1
                                val statResult = tuple.t2
                                
                                // 각 결과에 대한 에러 처리
                                when {
                                    infoResult.startsWith("2023년 12월 21일 이후의 접속 기록이 없습니다.") -> "2023년 12월 21일 이후의 접속 기록이 없습니다."
                                    infoResult.startsWith("API 오류 발생") -> "API 오류 발생"
                                    infoResult.startsWith("사용량이 많습니다. 다시 시도해주세요") -> "사용량이 많습니다. 다시 시도해주세요."
                                    infoResult.startsWith("Nexon API 서버 오류 발생") -> "Nexon API 서버 오류 발생"
                                    statResult.startsWith("2023년 12월 21일 이후의 접속 기록이 없습니다.") -> "2023년 12월 21일 이후의 접속 기록이 없습니다."
                                    statResult.startsWith("API 오류 발생") -> "API 오류 발생"
                                    statResult.startsWith("사용량이 많습니다. 다시 시도해주세요") -> "사용량이 많습니다. 다시 시도해주세요."
                                    statResult.startsWith("Nexon API 서버 오류 발생") -> "Nexon API 서버 오류 발생"
                                    else -> infoResult + statResult
                                }
                            }
                            .block() ?: "API 오류 발생"
                            
                        ResponseDto(result)
                    } catch (e: Exception) {
                        ResponseDto("API 오류 발생")
                    }
                }
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

    override fun history(characterName: String): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)
        return try {
            val ocid = fetchOcid(characterName)
            val result = when {
                ocid.startsWith("닉네임을 다시 확인해주세요") -> ResponseDto("닉네임을 다시 확인해주세요.")
                ocid.startsWith("API 오류 발생") -> ResponseDto("API 오류 발생")
                ocid.startsWith("사용량이 많습니다. 다시 시도해주세요.") -> ResponseDto("사용량이 많습니다. 다시 시도해주세요.")
                ocid.startsWith("Nexon API 서버 오류 발생") -> ResponseDto("Nexon API 서버 오류 발생")
                else -> {
                    try {
                        val lastWeekDates = getLastWeekDates()
                        val now = LocalDateTime.now()
                        val today = String.format("%04d-%02d-%02d", now.year, now.monthValue, now.dayOfMonth)
                        val expData: MutableList<String> = mutableListOf()
                        var isValidId = true
                        var currentLevel = 0
                        var currentExp = 0L

                        // expData(출력용) - 캐릭터명/월드, 레벨/퍼센트 등 기존 방식 유지
                        for (date in lastWeekDates) {
                            val cachedCharacterBasic: String? = redisService.getHistory(characterName, date)
                            if (cachedCharacterBasic != null) {
                                expData.add(cachedCharacterBasic)
                            } else {
                                val characterBasic: List<String>? = getHistory(ocid, date).block()
                                if (!characterBasic.isNullOrEmpty()) {
                                    if (characterBasic.size == 1) {
                                        if (characterBasic[0].startsWith("2023년")) {
                                            isValidId = false
                                            break
                                        }
                                    }
                                    // characterBasic[0]: 캐릭터명-월드, characterBasic[1]: 레벨/퍼센트
                                    expData.add(characterBasic[0])
                                    expData.add(characterBasic[1])
                                }
                            }
                        }

                        // 레벨업 계산용 경험치 이력 (공통화 함수 사용)
                        val levelExpHistory = getOrFetchLevelExpHistory(characterName, ocid, lastWeekDates)
                        if (levelExpHistory.isNotEmpty()) {
                            val (curLevel, curExp) = levelExpHistory.last()
                            currentLevel = curLevel
                            currentExp = curExp
                        }

                        // --- 예상 레벨업 날짜 추가 ---
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
                        // --- 메시지 조립 ---
                        val message = buildString {
                            if (expData.isEmpty()) {
                                if (isValidId) {
                                    append("히스토리 데이터가 없습니다.")
                                } else {
                                    append("2023년 12월 21일 이후의 접속 기록이 없습니다.")
                                }
                            } else {
                                append(expData[expData.size - 2]) // 캐릭터명 - 월드
                                append("\n")

                                for (i in 0..expData.size - 1) {
                                    if (i == expData.size - 2) continue
                                    append(expData[i])
                                    append("\n")
                                }

                                append(levelUpEstimateMsg)
                            }
                        }
                        ResponseDto(message)
                    } catch (e: Exception) {
                        ResponseDto("API 오류 발생")
                    }
                }
            }
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("history", parameters, result, processingTime)
            result
        } catch (e: Exception) {
            val processingTime = System.currentTimeMillis() - startTime
            apiLogService.logApiCall("history", parameters, null, processingTime, e.message)
            throw e
        }
    }

    override fun symbol(characterName: String): ResponseDto {
        val startTime = System.currentTimeMillis()
        val parameters = mapOf("characterName" to characterName)
        
        return try {
            val ocid = fetchOcid(characterName)
            
            val result = when {
                ocid.startsWith("닉네임을 다시 확인해주세요") -> ResponseDto("닉네임을 다시 확인해주세요.")
                ocid.startsWith("API 오류 발생") -> ResponseDto("API 오류 발생")
                ocid.startsWith("사용량이 많습니다. 다시 시도해주세요.") -> ResponseDto("사용량이 많습니다. 다시 시도해주세요.")
                ocid.startsWith("Nexon API 서버 오류 발생") -> ResponseDto("Nexon API 서버 오류 발생")
                else -> {
                    try {
                        val symbolResult = getSymbol(characterName, ocid).block() ?: "API 오류 발생"
                        ResponseDto(symbolResult)
                    } catch (e: Exception) {
                        ResponseDto("API 오류 발생")
                    }
                }
            }
            
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
            val ocid = fetchOcid(characterName)
            
            val result = when {
                ocid.startsWith("닉네임을 다시 확인해주세요") -> ResponseDto("닉네임을 다시 확인해주세요.")
                ocid.startsWith("API 오류 발생") -> ResponseDto("API 오류 발생")
                ocid.startsWith("사용량이 많습니다. 다시 시도해주세요.") -> ResponseDto("사용량이 많습니다. 다시 시도해주세요.")
                ocid.startsWith("Nexon API 서버 오류 발생") -> ResponseDto("Nexon API 서버 오류 발생")
                else -> {
                    try {
                        val abilityResult = getAbility(characterName, ocid).block() ?: "API 오류 발생"
                        ResponseDto(abilityResult)
                    } catch (e: Exception) {
                        ResponseDto("API 오류 발생")
                    }
                }
            }
            
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
        val parameters: Map<String, Any?> = mapOf("characterName" to characterName, "targetLevel" to targetLevel)
        return try {
            if (targetLevel == null) {
                return ResponseDto("목표 레벨을 입력해주세요. 예시: /레벨업 [목표레벨]")
            }

            val ocid = fetchOcid(characterName)
            if (ocid.startsWith("닉네임을 다시 확인해주세요") || ocid.startsWith("API 오류 발생") || ocid.startsWith("사용량이 많습니다. 다시 시도해주세요.") || ocid.startsWith("Nexon API 서버 오류 발생")) {
                return ResponseDto(ocid)
            }

            val lastWeekDates = getLastWeekDates()
            val levelExpHistory = getOrFetchLevelExpHistory(characterName, ocid, lastWeekDates)
            if (levelExpHistory.isEmpty()) {
                return ResponseDto("최근 경험치 이력이 없습니다. 먼저 /경험치히스토리 명령어로 데이터를 갱신해주세요.")
            }

            val (curLevel, curExp) = levelExpHistory.last()
            val avgExpGain = ExperienceUtil.calculateAverageExpGain(levelExpHistory)

            // 예외처리: 300 초과, 현재 레벨 이하
            if (curLevel >= 300) {
                return ResponseDto("이미 만렙(300)입니다.")
            }
            if (targetLevel > 300) {
                return ResponseDto("목표 레벨이 300을 초과합니다.")
            }
            if (targetLevel <= curLevel) {
                return ResponseDto("목표 레벨이 현재 레벨보다 낮거나 같습니다.")
            }
            if (avgExpGain <= 0) {
                return ResponseDto("경험치 증가량이 없어 예상 날짜를 계산할 수 없습니다.")
            }

            val requiredExp = ExperienceUtil.getExpToTargetLevel(curLevel, curExp, targetLevel)
            val estimatedDays = ceil(requiredExp.toDouble() / avgExpGain).toInt()
            val targetDate = LocalDate.now().plusDays(estimatedDays.toLong())
            val formattedDate = targetDate.format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"))

            val message = buildString {
                append("현재 레벨: $curLevel\n")
                append("현재 경험치: $curExp\n")
                append("목표 레벨: $targetLevel\n")
                append("필요 경험치: $requiredExp\n")
                append("최근 평균 일일 경험치 증가량: $avgExpGain\n")
                append("\n목표 레벨까지 예상 소요 기간: ${estimatedDays}일\n")
                append("예상 달성 날짜: $formattedDate")
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

    private fun fetchOcid(characterName: String): String {
        val cachedOcid = try {
            redisService.getOcid(characterName)
        } catch (e: Exception) {
            null  // Redis 오류 시 null 반환
        }

        if (cachedOcid != null) {
            return cachedOcid
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

    fun getInfo(ocid: String): Mono<String> {
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
                    400 -> Mono.just("2023년 12월 21일 이후의 접속 기록이 없습니다.")
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

    fun getStat(ocid: String): Mono<String> {
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
                    400 -> Mono.just("2023년 12월 21일 이후의 접속 기록이 없습니다.")
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

    fun getHistory(ocid: String, date: String): Mono<List<String>> {
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
                    Mono.just(listOf("2023년 12월 21일 이후의 접속 기록이 없습니다."))
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
                    400 -> Mono.just("2023년 12월 21일 이후의 접속 기록이 없습니다.")
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
                    400 -> Mono.just("2023년 12월 21일 이후의 접속 기록이 없습니다.")
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
            // redis에 없으면 api 호출 후 redis 저장
            val apiData = getHistory(ocid, date).block()
            if (apiData != null && apiData.size >= 4) {
                val level = apiData[2].toIntOrNull()
                val exp = apiData[3].toLongOrNull()
                if (level != null && exp != null) {
                    redisService.saveLevelExp(characterName, date, level, exp)
                    result.add(Pair(level, exp))
                }
            }
        }
        return result
    }
}