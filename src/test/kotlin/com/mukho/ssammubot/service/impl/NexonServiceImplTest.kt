package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.model.ResponseDto
import com.mukho.ssammubot.service.ApiLogService
import com.mukho.ssammubot.service.RedisService
import com.mukho.ssammubot.utils.CryptoUtil
import org.junit.jupiter.api.Assertions.assertEquals
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
        // Phase 0: 기존 코드는 OPENAPI00010 분기가 없어 기록 없음으로 반환됨
        assertEquals("2023년 12월 21일 이후의 접속 기록이 없습니다.", res.message)
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
        // Phase 0: 기존 코드는 공용 키 호출인데도 API Key 안내를 반환함
        assertEquals("유효하지 않은 API Key입니다. /키등록 명령어를 통해 다시 등록해주세요.", res.message)
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
        // Phase 0: 기존 코드는 사용자 키 호출인데도 일반 API 오류를 반환함
        assertEquals("API 오류 발생", res.message)
    }

    @Test
    fun `quest returns not own character message on OPENAPI00004`() {
        stubOcid()
        val encryptedKey = CryptoUtil.encrypt("user-api-key")
        routes["/maplestory/v1/scheduler/character-state"] = { jsonResponse(400, errorBody("OPENAPI00004")) }

        val res = nexonService.quest("묵호", encryptedKey)
        assertEquals("자신의 계정에 속한 캐릭터만 조회가 가능합니다.", res.message)
    }
}
