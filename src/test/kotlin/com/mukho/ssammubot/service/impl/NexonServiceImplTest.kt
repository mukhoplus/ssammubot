package com.mukho.ssammubot.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.mukho.ssammubot.client.GeminiClient
import com.mukho.ssammubot.model.ResponseDto
import com.mukho.ssammubot.model.SundayMapleGeminiResult
import com.mukho.ssammubot.model.SundayMapleInfo
import com.mukho.ssammubot.service.ApiLogService
import com.mukho.ssammubot.service.RedisService
import com.mukho.ssammubot.utils.CryptoUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NexonServiceImplTest {

    data class LogEntry(
        val apiName: String,
        val parameters: Map<String, Any?>,
        val result: Any?,
        val processingTime: Long,
        val error: String?
    )

    class FakeRedisService : RedisService {
        val ocidStore = ConcurrentHashMap<String, String>()
        val historyStore = ConcurrentHashMap<Pair<String, String>, String>()
        val levelExpStore = ConcurrentHashMap<Pair<String, String>, Pair<Int, Long>>()
        @Volatile var sundayMapleJson: String? = null
        @Volatile var sundayMapleTtl: Duration? = null
        val sundayDeleteCount = AtomicInteger(0)

        override fun saveOcid(characterName: String, ocid: String) {
            ocidStore[characterName] = ocid
        }

        override fun getOcid(characterName: String): String? = ocidStore[characterName]

        override fun deleteOcid(characterName: String) {
            ocidStore.remove(characterName)
        }

        override fun saveHistory(characterName: String, date: String, value: String) {
            historyStore[characterName to date] = value
        }

        override fun getHistory(characterName: String, date: String): String? =
            historyStore[characterName to date]

        override fun saveLevelExp(characterName: String, date: String, level: Int, exp: Long) {
            levelExpStore[characterName to date] = level to exp
        }

        override fun getLevelExp(characterName: String, date: String): Pair<Int, Long>? =
            levelExpStore[characterName to date]

        override fun getLevelExpHistory(characterName: String, dates: List<String>): List<Pair<Int, Long>> =
            dates.mapNotNull { levelExpStore[characterName to it] }

        override fun saveSundayMaple(json: String, ttl: Duration) {
            sundayMapleJson = json
            sundayMapleTtl = ttl
        }

        override fun getSundayMaple(): String? = sundayMapleJson

        override fun deleteSundayMaple() {
            sundayMapleJson = null
            sundayMapleTtl = null
            sundayDeleteCount.incrementAndGet()
        }
    }

    class RecordingApiLogService : ApiLogService {
        val logs = CopyOnWriteArrayList<LogEntry>()

        override fun logApiCall(
            apiName: String,
            parameters: Map<String, Any?>,
            result: Any?,
            processingTime: Long,
            error: String?
        ) {
            logs.add(LogEntry(apiName, parameters, result, processingTime, error))
        }

        override fun logExternalApiCall(
            externalApiUrl: String,
            parameters: Map<String, Any>,
            statusCode: Int,
            responseBody: String?,
            processingTime: Long
        ) = Unit
    }

    private lateinit var redisService: FakeRedisService
    private lateinit var apiLogService: RecordingApiLogService
    private lateinit var routes: MutableMap<String, (ClientRequest) -> ClientResponse>
    private lateinit var callCounts: ConcurrentHashMap<String, AtomicInteger>
    private lateinit var nexonService: NexonServiceImpl

    @BeforeEach
    fun setUp() {
        redisService = FakeRedisService()
        apiLogService = RecordingApiLogService()
        routes = ConcurrentHashMap()
        callCounts = ConcurrentHashMap()

        val exchangeFunction = ExchangeFunction { request ->
            val path = request.url().path
            callCounts.computeIfAbsent(path) { AtomicInteger(0) }.incrementAndGet()
            val handler = routes[path]
                ?: return@ExchangeFunction Mono.error(IllegalStateException("Unmocked path: $path"))
            try {
                Mono.just(handler(request))
            } catch (e: Exception) {
                Mono.error(e)
            }
        }

        val webClient = WebClient.builder()
            .baseUrl("https://open.api.nexon.com/maplestory/v1")
            .exchangeFunction(exchangeFunction)
            .build()

        nexonService = NexonServiceImpl(webClient, redisService, apiLogService)
    }

    private fun jsonResponse(status: Int, body: String): ClientResponse {
        return ClientResponse.create(HttpStatus.valueOf(status))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build()
    }

    private fun errorBody(errorName: String, message: String = "error"): String {
        return """{"error":{"name":"$errorName","message":"$message"}}"""
    }

    private fun stubOcid(ocid: String = "test-ocid") {
        routes["/maplestory/v1/id"] = { jsonResponse(200, """{"ocid":"$ocid"}""") }
    }

    // ==================== scouter ====================

    @Test
    fun `scouter returns link when ocid succeeds`() {
        stubOcid()
        val res = nexonService.scouter("묵호")
        assertEquals("https://maplescouter.com/info?name=묵호", res.message)
    }

    @Test
    fun `scouter returns error message when ocid returns 400`() {
        routes["/maplestory/v1/id"] = { jsonResponse(400, errorBody("OPENAPI00004")) }
        val res = nexonService.scouter("없는케릭")
        assertEquals("닉네임을 다시 확인해주세요.", res.message)
    }

    @Test
    fun `scouter returns rate limit message when ocid returns 429`() {
        routes["/maplestory/v1/id"] = { jsonResponse(429, errorBody("OPENAPI00007")) }
        val res = nexonService.scouter("묵호")
        assertEquals("사용량이 많습니다. 다시 시도해주세요.", res.message)
    }

    // ==================== info ====================

    @Test
    fun `info formats basic, stat and union on success`() {
        stubOcid()
        routes["/maplestory/v1/character/basic"] = {
            jsonResponse(
                200,
                """{"character_name":"묵호","world_name":"스카니아","character_guild_name":"길드","character_class":"아크메이지(불,독)","character_level":285}"""
            )
        }
        routes["/maplestory/v1/character/stat"] = {
            jsonResponse(
                200,
                """{"character_class":"아크메이지(불,독)","final_stat":[{"stat_name":"전투력","stat_value":"123456789"}],"remain_ap":0}"""
            )
        }
        routes["/maplestory/v1/user/union"] = {
            jsonResponse(
                200,
                """{"union_level":9000,"union_grade":"그랜드 마스터 유니온 3","union_artifact_level":45}"""
            )
        }

        val res = nexonService.info("묵호")
        val expected = """
            묵호
            스카니아@길드
            아크메이지(불,독) | Lv.285
            전투력: 1억 2345만 6789
            유니온: Lv.9000 / 그랜드 마스터 유니온 3
            아티팩트: Lv.45
        """.trimIndent()
        assertEquals(expected, res.message)
    }

    @Test
    fun `info retries ocid once when basic returns OPENAPI00003`() {
        redisService.saveOcid("묵호", "stale-ocid")
        routes["/maplestory/v1/id"] = { jsonResponse(200, """{"ocid":"fresh-ocid"}""") }

        routes["/maplestory/v1/character/basic"] = { req ->
            if (req.url().query.contains("stale-ocid")) {
                jsonResponse(400, errorBody("OPENAPI00003"))
            } else {
                jsonResponse(
                    200,
                    """{"character_name":"묵호","world_name":"스카니아","character_class":"비숍","character_level":280}"""
                )
            }
        }
        routes["/maplestory/v1/character/stat"] = {
            jsonResponse(
                200,
                """{"character_class":"비숍","final_stat":[{"stat_name":"전투력","stat_value":"50000"}],"remain_ap":0}"""
            )
        }
        routes["/maplestory/v1/user/union"] = { jsonResponse(400, errorBody("OPENAPI00004")) }

        val res = nexonService.info("묵호")
        assertTrue(res.message.contains("비숍 | Lv.280"))
        assertEquals(1, callCounts["/maplestory/v1/id"]?.get())
        assertEquals("fresh-ocid", redisService.getOcid("묵호"))
    }

    @Test
    fun `info returns maintenance message on OPENAPI00010`() {
        stubOcid()
        routes["/maplestory/v1/character/basic"] = { jsonResponse(400, errorBody("OPENAPI00010")) }
        routes["/maplestory/v1/character/stat"] = { jsonResponse(400, errorBody("OPENAPI00010")) }
        routes["/maplestory/v1/user/union"] = { jsonResponse(400, errorBody("OPENAPI00010")) }

        val res = nexonService.info("묵호")
        assertEquals("정보 - 서버 점검 중에는 이용 불가합니다.", res.message)
    }

    // ==================== symbol ====================

    @Test
    fun `symbol formats equipped symbols`() {
        stubOcid()
        routes["/maplestory/v1/character/symbol-equipment"] = {
            jsonResponse(
                200,
                """{
                    "character_class":"비숍",
                    "symbol":[
                        {"symbol_name":"아케인심볼 : 소멸의 여로","symbol_icon":"","symbol_description":"","symbol_force":"220","symbol_level":20,"symbol_str":"0","symbol_dex":"0","symbol_int":"2200","symbol_luk":"0","symbol_drop_rate":"0%","symbol_meso_rate":"0%","symbol_exp_rate":"0%","symbol_growth_count":0,"symbol_require_growth_count":0},
                        {"symbol_name":"그랜드 어센틱심볼 : 탈라하트","symbol_icon":"","symbol_description":"","symbol_force":"50","symbol_level":5,"symbol_str":"0","symbol_dex":"0","symbol_int":"0","symbol_luk":"0","symbol_drop_rate":"0%","symbol_meso_rate":"0%","symbol_exp_rate":"0%","symbol_growth_count":0,"symbol_require_growth_count":0}
                    ]
                }"""
            )
        }

        val res = nexonService.symbol("묵호")
        assertTrue(res.message.contains("여로 Lv.20 / 츄츄 Lv.0"))
        assertTrue(res.message.contains("탈라하트 Lv.5"))
    }

    @Test
    fun `symbol returns maintenance message on OPENAPI00010`() {
        stubOcid()
        routes["/maplestory/v1/character/symbol-equipment"] = { jsonResponse(400, errorBody("OPENAPI00010")) }

        val res = nexonService.symbol("묵호")
        assertEquals("정보 - 서버 점검 중에는 이용 불가합니다.", res.message)
    }

    @Test
    fun `symbol returns api maintenance message on 503 OPENAPI00011`() {
        stubOcid()
        routes["/maplestory/v1/character/symbol-equipment"] = { jsonResponse(503, errorBody("OPENAPI00011")) }

        val res = nexonService.symbol("묵호")
        assertEquals("Nexon API 점검 중입니다.", res.message)
    }

    @Test
    fun `symbol returns data preparing message on OPENAPI00009`() {
        stubOcid()
        routes["/maplestory/v1/character/symbol-equipment"] = { jsonResponse(400, errorBody("OPENAPI00009")) }

        val res = nexonService.symbol("묵호")
        assertEquals("데이터 준비 중입니다. 잠시 후 다시 시도해주세요.", res.message)
    }

    // ==================== abil ====================

    @Test
    fun `abil formats ability presets with active preset marker`() {
        stubOcid()
        routes["/maplestory/v1/character/ability"] = {
            jsonResponse(
                200,
                """{
                    "ability_grade":"레전드리",
                    "ability_info":[],
                    "remain_fame":1000,
                    "preset_no":2,
                    "ability_preset_1":{"ability_preset_grade":"레전드리","ability_info":[{"ability_no":"1","ability_grade":"레전드리","ability_value":"보스 몬스터 공격 시 데미지 20% 증가"}]},
                    "ability_preset_2":{"ability_preset_grade":"레전드리","ability_info":[{"ability_no":"1","ability_grade":"레전드리","ability_value":"메소 획득량 20% 증가"}]},
                    "ability_preset_3":{"ability_preset_grade":"유니크","ability_info":[]}
                }"""
            )
        }

        val res = nexonService.abil("묵호")
        assertTrue(res.message.contains("프리셋 1[레전드리]"))
        assertTrue(res.message.contains("* 프리셋 2[레전드리]"))
        assertTrue(res.message.contains("프리셋 3[유니크]"))
    }

    // ==================== ring ====================

    @Test
    fun `ring formats main and reserve special rings`() {
        stubOcid()
        routes["/maplestory/v1/character/item-equipment"] = {
            jsonResponse(
                200,
                """{
                    "item_equipment":[
                        {"item_equipment_part":"반지","item_equipment_slot":"반지4","item_name":"리스트레인트 링","special_ring_level":4},
                        {"item_equipment_part":"반지","item_equipment_slot":"예비 특수 반지","item_name":"컨티뉴어스 링","special_ring_level":4}
                    ]
                }"""
            )
        }

        val res = nexonService.ring("묵호")
        val expected = """
            묵호 - 시드링 정보
            시드링: 리스트레인트 링 Lv.4
            보조 시드링: 컨티뉴어스 링 Lv.4
        """.trimIndent()
        assertEquals(expected, res.message)
    }

    @Test
    fun `ring 403 error message`() {
        stubOcid()
        routes["/maplestory/v1/character/item-equipment"] = { jsonResponse(403, errorBody("OPENAPI00002")) }

        val res = nexonService.ring("묵호")
        assertEquals("API 오류 발생", res.message)
    }

    // ==================== history & levelUp ====================

    @Test
    fun `history returns no record message on 400 unknown error`() {
        stubOcid()
        routes["/maplestory/v1/character/basic"] = { jsonResponse(400, errorBody("OPENAPI00004")) }

        val res = nexonService.history("묵호")
        assertEquals("2023년 12월 21일 이후의 접속 기록이 없습니다.", res.message)
    }

    @Test
    fun `history propagates maintenance and rate limit errors without IndexOutOfBounds`() {
        stubOcid()
        routes["/maplestory/v1/character/basic"] = { jsonResponse(429, errorBody("OPENAPI00007")) }

        val res = nexonService.history("묵호")
        assertEquals("사용량이 많습니다. 다시 시도해주세요.", res.message)
    }

    @Test
    fun `early return on error still records apiLogService call`() {
        routes["/maplestory/v1/id"] = { jsonResponse(400, errorBody("OPENAPI00004")) }

        val res = nexonService.scouter("없는케릭")
        assertEquals("닉네임을 다시 확인해주세요.", res.message)
        assertEquals(1, apiLogService.logs.size)
        assertEquals("scouter", apiLogService.logs[0].apiName)
        assertEquals(ResponseDto("닉네임을 다시 확인해주세요."), apiLogService.logs[0].result)
    }

    @Test
    fun `levelUp validates max level 300`() {
        stubOcid()
        routes["/maplestory/v1/character/basic"] = {
            jsonResponse(
                200,
                """{"character_name":"묵호","world_name":"스카니아","character_level":300,"character_exp":0,"character_exp_rate":"0.000"}"""
            )
        }

        val res = nexonService.levelUp("묵호", null)
        assertEquals("묵호 - 스카니아\n이미 만렙(300)입니다.", res.message)
    }

    // ==================== encryptKey, boss, quest ====================

    @Test
    fun `encryptKey masks result in apiLogService`() {
        val res = nexonService.encryptKey(" test-raw-key ")
        assertEquals("test-raw-key", CryptoUtil.decrypt(res.message))
        assertEquals(1, apiLogService.logs.size)
        assertEquals("encryptKey", apiLogService.logs[0].apiName)
        assertEquals(ResponseDto("********"), apiLogService.logs[0].result)
    }

    @Test
    fun `boss returns registration guide when apiKey is blank`() {
        val res = nexonService.boss("묵호", "")
        assertEquals(
            "/키등록 명령어를 통해 NEXON Open API에서 발급받은 본인의 API_KEY를 등록해주세요.\n사용법: 키등록 [API_KEY]",
            res.message
        )
    }

    @Test
    fun `boss returns invalid apiKey message when decrypt fails`() {
        val res = nexonService.boss("묵호", "invalid-cipher-text")
        assertEquals("유효하지 않은 API Key입니다. /키등록 명령어를 통해 다시 등록해주세요.", res.message)
    }

    @Test
    fun `boss formats weekly and monthly bosses`() {
        stubOcid()
        val encryptedKey = CryptoUtil.encrypt("user-api-key")
        routes["/maplestory/v1/scheduler/character-state"] = {
            jsonResponse(
                200,
                """{
                    "weekly_boss_clear_count":1,
                    "weekly_boss_clear_limit_count":12,
                    "boss_contents":[
                        {"content_name":"세렌","difficulty":"hard","cycle":"bossWeekly","list_order_no":1,"registration_flag":"true","complete_flag":"true"},
                        {"content_name":"검은 마법사","difficulty":"hard","cycle":"bossMonthly","list_order_no":1,"registration_flag":"true","complete_flag":"false"}
                    ]
                }"""
            )
        }

        val res = nexonService.boss("묵호", encryptedKey)
        val expected = """
            묵호 - 보스 정보
            [주간 보스] (1/12)
            - 세렌(하드): 완료
            [월간 보스]
            - 검은 마법사(하드): 미완료
        """.trimIndent()
        assertEquals(expected, res.message)
    }

    @Test
    fun `boss 403 error message`() {
        stubOcid()
        val encryptedKey = CryptoUtil.encrypt("user-api-key")
        routes["/maplestory/v1/scheduler/character-state"] = { jsonResponse(403, errorBody("OPENAPI00002")) }

        val res = nexonService.boss("묵호", encryptedKey)
        assertEquals("유효하지 않은 API Key입니다. /키등록 명령어를 통해 다시 등록해주세요.", res.message)
    }

    @Test
    fun `boss returns invalid apiKey message when both apiKey and nickname are invalid`() {
        val encryptedKey = CryptoUtil.encrypt("invalid-nexon-api-key")
        routes["/maplestory/v1/id"] = { req ->
            assertEquals("invalid-nexon-api-key", req.headers().getFirst("x-nxopen-api-key"))
            jsonResponse(400, errorBody("OPENAPI00005"))
        }

        val res = nexonService.boss("없는닉네임", encryptedKey)
        assertEquals("유효하지 않은 API Key입니다. /키등록 명령어를 통해 다시 등록해주세요.", res.message)
    }

    @Test
    fun `quest returns not own character message on OPENAPI00004`() {
        stubOcid()
        val encryptedKey = CryptoUtil.encrypt("user-api-key")
        routes["/maplestory/v1/scheduler/character-state"] = { jsonResponse(400, errorBody("OPENAPI00004")) }

        val res = nexonService.quest("묵호", encryptedKey)
        assertEquals("자신의 계정에 속한 캐릭터만 조회가 가능합니다.", res.message)
    }

    // ==================== sunday ====================

    private fun buildServiceWithClock(
        isoKstDateTime: String,
        geminiClient: GeminiClient = GeminiClient { _, _ ->
            SundayMapleGeminiResult(
                eventDate = "2026년 11월 1일(일)",
                isSpecial = false,
                benefits = listOf("스타포스 강화 비용 30% 할인", "몬스터파크 클리어 경험치 +50%")
            )
        }
    ): NexonServiceImpl {
        val kst = ZoneId.of("Asia/Seoul")
        val fixedClock = Clock.fixed(ZonedDateTime.parse(isoKstDateTime).toInstant(), kst)
        val exchangeFunction = ExchangeFunction { request ->
            val path = request.url().path
            callCounts.computeIfAbsent(path) { AtomicInteger(0) }.incrementAndGet()
            val handler = routes[path]
                ?: return@ExchangeFunction Mono.error(IllegalStateException("Unmocked path: $path"))
            try {
                Mono.just(handler(request))
            } catch (e: Exception) {
                Mono.error(e)
            }
        }
        val testWebClient = WebClient.builder()
            .baseUrl("https://open.api.nexon.com/maplestory/v1")
            .exchangeFunction(exchangeFunction)
            .build()
        val testImageWebClient = WebClient.builder()
            .exchangeFunction(exchangeFunction)
            .build()

        return NexonServiceImpl(
            webClient = testWebClient,
            redisService = redisService,
            apiLogService = apiLogService,
            imageWebClient = testImageWebClient,
            geminiClient = geminiClient,
            objectMapper = ObjectMapper(),
            clock = fixedClock
        )
    }

    @Test
    fun `sunday returns waiting message during Monday to Friday before 10 AM without calling external API`() {
        // Thursday 2026-10-29 23:00:00 KST
        val service = buildServiceWithClock("2026-10-29T23:00:00+09:00")
        val res = service.sunday()

        assertEquals("이번 주 썬데이 메이플 공지는 금요일 오전 10시에 공개됩니다.", res.message)
        assertNull(callCounts["/maplestory/v1/notice-event"])
    }

    @Test
    fun `sunday returns cached data on valid cache hit without calling external API`() {
        val service = buildServiceWithClock("2026-10-30T12:00:00+09:00")
        val cachedInfo = SundayMapleInfo(
            noticeId = 987L,
            title = "썬데이 메이플",
            url = "https://maplestory.nexon.com/News/Event/Ongoing/987",
            imageUrl = "https://lwi.nexon.com/sunday.jpg",
            eventDate = "2026년 11월 1일(일)",
            isSpecial = true,
            benefits = listOf("어빌리티 재설정 비용 50% 할인", "룬 경험치 버프 효과 +100%"),
            eventEndAt = "2026-11-01T23:59:00+09:00",
            cachedAt = "2026-10-30T10:00:10",
            expiresAt = "2026-11-02T00:00:00"
        )
        redisService.saveSundayMaple(ObjectMapper().writeValueAsString(cachedInfo), Duration.ofHours(60))

        val res = service.sunday()
        val expected = """
            [스페셜 썬데이 메이플]
            일시: 2026년 11월 1일(일)

            * 주요 혜택
            - 어빌리티 재설정 비용 50% 할인
            - 룬 경험치 버프 효과 +100%

            공지 링크: https://maplestory.nexon.com/News/Event/Ongoing/987
        """.trimIndent()

        assertEquals(expected, res.message)
        assertNull(callCounts["/maplestory/v1/notice-event"])
    }

    @Test
    fun `sunday deletes expired or corrupted cache immediately and re-runs pipeline`() {
        // Friday 2026-10-30 10:00:10 KST
        val service = buildServiceWithClock("2026-10-30T10:00:10+09:00")
        // Put expired cache from previous week
        val expiredInfo = SundayMapleInfo(
            noticeId = 900L,
            title = "썬데이 메이플",
            url = "https://maplestory.nexon.com/News/Event/Ongoing/900",
            imageUrl = "https://lwi.nexon.com/old.jpg",
            eventDate = "2026년 10월 25일(일)",
            isSpecial = false,
            benefits = listOf("이전 혜택"),
            eventEndAt = "2026-10-25T23:59:00+09:00",
            cachedAt = "2026-10-23T10:00:10",
            expiresAt = "2026-10-26T00:00:00"
        )
        redisService.saveSundayMaple(ObjectMapper().writeValueAsString(expiredInfo), Duration.ofHours(1))

        routes["/maplestory/v1/notice-event"] = {
            jsonResponse(
                200,
                """{"event_notice":[{"title":"썬데이 메이플","url":"https://maplestory.nexon.com/987","notice_id":987,"date":"2026-10-30T10:00+09:00","date_event_start":"2026-11-01T00:00+09:00","date_event_end":"2026-11-01T23:59+09:00"}]}"""
            )
        }
        routes["/maplestory/v1/notice-event/detail"] = {
            jsonResponse(
                200,
                """{"title":"썬데이 메이플","url":"https://maplestory.nexon.com/987","contents":"<div><img src=\"https://lwi.nexon.com/sunday987.jpg\"></div>","date":"2026-10-30T10:00+09:00","date_event_start":"2026-11-01T00:00+09:00","date_event_end":"2026-11-01T23:59+09:00"}"""
            )
        }
        routes["/sunday987.jpg"] = {
            ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .body("fake-image-bytes")
                .build()
        }

        val res = service.sunday()

        assertEquals(1, redisService.sundayDeleteCount.get())
        assertTrue(res.message.contains("스타포스 강화 비용 30% 할인"))
        assertNotNull(redisService.getSundayMaple())
        // Friday 10:00:10 -> Monday 00:00:00 = 61h 59m 50s = 223,190 seconds
        assertEquals(Duration.ofSeconds(223_190), redisService.sundayMapleTtl)
    }

    @Test
    fun `sunday returns preparing message when only last week expired notice exists`() {
        // Friday 2026-10-30 10:00:00 KST
        val service = buildServiceWithClock("2026-10-30T10:00:00+09:00")
        routes["/maplestory/v1/notice-event"] = {
            jsonResponse(
                200,
                """{"event_notice":[{"title":"썬데이 메이플","url":"https://maplestory.nexon.com/900","notice_id":900,"date":"2026-10-23T10:00+09:00","date_event_start":"2026-10-25T00:00+09:00","date_event_end":"2026-10-25T23:59+09:00"}]}"""
            )
        }

        val res = service.sunday()

        assertEquals("아직 이번 주 썬데이 메이플 공지가 등록되지 않았습니다. 잠시 후 다시 시도해주세요.", res.message)
        assertNull(redisService.getSundayMaple())
    }

    @Test
    fun `preloadSundayMaple and concurrent sunday requests prevent duplicate Gemini API calls`() {
        val geminiCallCount = AtomicInteger(0)
        val insideGeminiLatch = CountDownLatch(1)
        val proceedGeminiLatch = CountDownLatch(1)

        val slowGeminiClient = GeminiClient { _, _ ->
            geminiCallCount.incrementAndGet()
            insideGeminiLatch.countDown()
            proceedGeminiLatch.await(5, TimeUnit.SECONDS)
            SundayMapleGeminiResult(
                eventDate = "2026년 11월 1일(일)",
                isSpecial = false,
                benefits = listOf("스타포스 30% 할인")
            )
        }

        val service = buildServiceWithClock("2026-10-30T10:00:10+09:00", slowGeminiClient)
        routes["/maplestory/v1/notice-event"] = {
            jsonResponse(
                200,
                """{"event_notice":[{"title":"썬데이 메이플","url":"https://maplestory.nexon.com/987","notice_id":987,"date":"2026-10-30T10:00+09:00","date_event_start":"2026-11-01T00:00+09:00","date_event_end":"2026-11-01T23:59+09:00"}]}"""
            )
        }
        routes["/maplestory/v1/notice-event/detail"] = {
            jsonResponse(
                200,
                """{"title":"썬데이 메이플","url":"https://maplestory.nexon.com/987","contents":"<img src='https://lwi.nexon.com/sunday987.jpg'/>","date":"2026-10-30T10:00+09:00","date_event_start":"2026-11-01T00:00+09:00","date_event_end":"2026-11-01T23:59+09:00"}"""
            )
        }
        routes["/sunday987.jpg"] = {
            ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                .body("fake-image-bytes")
                .build()
        }

        val executor = Executors.newFixedThreadPool(2)
        try {
            // Thread 1 starts preload (simulating 10:00:10 scheduler run)
            val firstFuture = executor.submit<Boolean> { service.preloadSundayMaple() }
            assertTrue(insideGeminiLatch.await(3, TimeUnit.SECONDS))

            // While Thread 1 is parsing in Gemini, next scheduler tick (10:00:20) immediately skips via tryLock()
            assertFalse(service.preloadSundayMaple())

            // Concurrent client request waits on lock and reads cached result once Thread 1 finishes
            val clientFuture = executor.submit<ResponseDto> { service.sunday() }

            proceedGeminiLatch.countDown()

            assertTrue(firstFuture.get(5, TimeUnit.SECONDS))
            val clientResult = clientFuture.get(5, TimeUnit.SECONDS)
            assertTrue(clientResult.message.contains("스타포스 30% 할인"))

            // Subsequent scheduler tick (10:00:30) hits Redis cache and returns true immediately without API calls
            assertTrue(service.preloadSundayMaple())

            // Only 1 call to Nexon API and 1 call to Gemini API occurred across all threads
            assertEquals(1, callCounts["/maplestory/v1/notice-event"]?.get())
            assertEquals(1, geminiCallCount.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
