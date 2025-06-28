package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.model.*
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
    private val redisService: RedisService
): NexonService {

    override fun scouter(characterName: String): ResponseDto {
        val ocid = try {
            redisService.getOcid(characterName)
        } catch (e: Exception) {
            null  // 500 예외(Redis 오류) 발생 시 null 반환
        } ?: getOcid(characterName).block() ?: "API 오류 발생"

        return if (ocid.startsWith("API 오류 발생")) {
            ResponseDto("닉네임을 다시 확인해주세요.")
        } else {
            ResponseDto("https://maplescouter.com/info?name=$characterName")
        }
    }

    override fun info(characterName: String): ResponseDto {
        val ocid = try {
            redisService.getOcid(characterName)
        } catch (e: Exception) {
            null  // 500 예외(Redis 오류) 발생 시 null 반환
        } ?: getOcid(characterName).block() ?: "API 오류 발생"

        if (ocid.startsWith("API 오류 발생")) {
            return ResponseDto("닉네임을 다시 확인해주세요.")
        }

        return try {
            Mono.zip(getInfo(ocid), getStat(ocid))
                .map { ResponseDto(it.t1 + it.t2) }
                .block() ?: ResponseDto("API 오류 발생")
        } catch (e: Exception) {
            return ResponseDto("API 오류 발생")
        }
    }

    override fun history(characterName: String): ResponseDto {
        val ocid = try {
            redisService.getOcid(characterName)
        } catch (e: Exception) {
            null  // 500 예외(Redis 오류) 발생 시 null 반환
        } ?: getOcid(characterName).block() ?: return ResponseDto("API 오류 발생")

        try {
            val expData: MutableList<String> = mutableListOf();
            val lastWeekDates: List<String> = getLastWeekDates()

            val now = LocalDateTime.now()
            val today = String.format("%04d-%02d-%02d", now.year, now.monthValue, now.dayOfMonth)


            for (date in lastWeekDates) {
                // 오늘(갱신 전 실시간) 데이터는 API 호출, Redis에 저장하지 않음
                if ( (now.hour < 9 && LocalDate.parse(date).plusDays(1).toString() == today)
                    || (now.hour >= 9 && date == today) ) { // 내일 날짜와 비교
                    val characterBasic: List<String>? = getHistory(ocid, date).block()
                    if (characterBasic.isNullOrEmpty()) continue

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
                        expData.add(characterBasic[1])
                    }
                }
            }

            val message = buildString {
                for (i in 0 .. expData.size - 2) {
                    append(expData[i])
                    append("\n")
                }
                append(expData[expData.size - 1])
            }

            return ResponseDto(message);
        } catch (e: Exception) {
            return ResponseDto("API 오류 발생")
        }
    }

    fun getOcid(characterName: String): Mono<String> {
        return webClient.get()
            .uri("/id?character_name=$characterName")
            .exchangeToMono { response ->
                if (response.statusCode().is2xxSuccessful) {
                    response.bodyToMono(CharacterDto::class.java)
                        .map {
                            redisService.saveOcid(characterName, it.ocid)
                            it.ocid }
                } else {
                    response.bodyToMono(ErrorMessageDto::class.java)
                        .flatMap { errorBody ->
                            println(errorBody.error.message)
                            Mono.error(RuntimeException("API 오류 발생: ${errorBody.error.message}"))
                        }
                }
            }
            .onErrorResume { ex ->
                Mono.just("API 오류 발생")
            }
    }

    fun getInfo(ocid: String): Mono<String> {
        return webClient.get()
            .uri("/character/basic?ocid=$ocid")
            .exchangeToMono { response ->
                if (response.statusCode().is2xxSuccessful) {
                    response.bodyToMono(CharacterBasicDto::class.java)
                        .map { it ->
                            """
                            ${it.character_name}
                            ${it.world_name}${if (it.character_guild_name != null) "@${it.character_guild_name}" else ""}
                            ${it.character_class} | Lv.${it.character_level}
                            
                            """.trimIndent()
                        }
                } else {
                    response.bodyToMono(ErrorMessageDto::class.java)
                        .flatMap { errorBody ->
                            println(errorBody.error.message)
                            Mono.error(RuntimeException("API 오류 발생: ${errorBody.error.message}"))
                        }
                }
            }
            .onErrorResume { ex ->
                println(ex)
                Mono.just("API 오류 발생")
            }
    }

    fun getStat(ocid: String): Mono<String> {
        return webClient.get()
            .uri("/character/stat?ocid=$ocid")
            .exchangeToMono { response ->
                if (response.statusCode().is2xxSuccessful) {
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
                } else {
                    response.bodyToMono(ErrorMessageDto::class.java)
                        .flatMap { errorBody ->
                            println(errorBody.error.message)
                            Mono.error(RuntimeException("API 오류 발생: ${errorBody.error.message}"))
                        }
                }
            }
            .onErrorResume { ex ->
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
        val startDay = if (now.hour < 9) 1L else 0L

        return ((startDay + 6) downTo startDay).map { getDate(it) }
    }

    fun getHistory(ocid: String, date: String): Mono<List<String>> {
        val now = LocalDateTime.now()
        val today = getDate(0)

        val isOneDayBehind = LocalDate.parse(date).plusDays(1).toString() == today && now.hour < 9
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
                                || dto.character_exp_rate == null) {
                                Mono.just(emptyList()) // 유효하지 않은 데이터 → 빈 리스트 반환
                            } else {
                                val characterInfo = "${dto.character_name} - ${dto.world_name}"
                                val formattedDate = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MM월 dd일"))
                                val levelInfo = "Lv.${dto.character_level} ${dto.character_exp_rate}%"

                                // 캐싱 조건 검사 (오늘 데이터 제외)
                                val isSpecialCase = (now.hour < 9 && LocalDate.parse(date).plusDays(1).toString() == today) ||
                                        (now.hour >= 9 && date == today)
                                if (!isSpecialCase) {
                                    redisService.saveHistory(dto.character_name, date, "$formattedDate : $levelInfo")
                                }

                                Mono.just(listOf(characterInfo, "$formattedDate : $levelInfo"))
                            }
                        }
                } else {
                    // 오류 발생 시 빈 리스트 반환 (스킵 처리)
                    Mono.just(emptyList())
                }
            }
            .onErrorResume {
                Mono.just(emptyList()) // 예외 발생 시 빈 리스트 반환
            }
    }
}