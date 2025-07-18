package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.model.*
import com.mukho.ssammubot.service.ApiLogService
import com.mukho.ssammubot.service.NexonService
import com.mukho.ssammubot.service.RedisService
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
                        val expData: MutableList<String> = mutableListOf()
                        val lastWeekDates: List<String> = getLastWeekDates()

                        val now = LocalDateTime.now()
                        val today = String.format("%04d-%02d-%02d", now.year, now.monthValue, now.dayOfMonth)

                        var isValidId = true

                        for (date in lastWeekDates) {
                            // 오늘(갱신 전 실시간) 데이터는 API 호출, Redis에 저장하지 않음
                            if ( (now.hour < 6 && LocalDate.parse(date).plusDays(1).toString() == today)
                                || (now.hour >= 6 && date == today) ) { // 내일 날짜와 비교
                                val characterBasic = getHistory(ocid, date).block()
                                if (characterBasic.isNullOrEmpty()) continue

                                if (characterBasic.size == 1) {
                                    if (characterBasic[0].startsWith("2023년")) {
                                        isValidId = false
                                        break
                                    }
                                }

                                expData.add(0, characterBasic[0])
                                expData.add(characterBasic[1])
                                continue
                            }

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

                                    expData.add(characterBasic[1])
                                }
                            }
                        }

                        val message = buildString {
                            if (expData.isEmpty()) {
                                if (isValidId) {
                                    append("히스토리 데이터가 없습니다.")
                                } else {
                                    append("2023년 12월 21일 이후의 접속 기록이 없습니다.")
                                }
                            } else {
                                for (i in 0 .. expData.size - 2) {
                                    append(expData[i])
                                    append("\n")
                                }
                                append(expData[expData.size - 1])
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
                            ) {
                                Mono.just(emptyList()) // 유효하지 않은 데이터 → 빈 리스트 반환
                            } else {
                                val characterInfo = "${dto.character_name} - ${dto.world_name}"
                                val formattedDate = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MM월 dd일"))
                                val levelInfo = "Lv.${dto.character_level} ${dto.character_exp_rate}%"

                                // 캐싱 조건 검사 (오늘 데이터 제외)
                                val isSpecialCase =
                                    (now.hour < 6 && LocalDate.parse(date).plusDays(1).toString() == today) ||
                                            (now.hour >= 6 && date == today)
                                if (!isSpecialCase) {
                                    redisService.saveHistory(dto.character_name, date, "$formattedDate : $levelInfo")
                                }

                                Mono.just(listOf(characterInfo, "$formattedDate : $levelInfo"))
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
}