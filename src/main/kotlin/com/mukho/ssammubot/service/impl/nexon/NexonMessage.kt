package com.mukho.ssammubot.service.impl.nexon

internal object NexonMessage {
    const val API_ERROR = "API 오류 발생"
    const val CHECK_NICKNAME = "닉네임을 다시 확인해주세요."
    const val TOO_MANY_REQUESTS = "사용량이 많습니다. 다시 시도해주세요."
    const val SERVER_ERROR = "Nexon API 서버 오류 발생"
    const val MAINTENANCE = "정보 - 서버 점검 중에는 이용 불가합니다."
    const val API_MAINTENANCE = "Nexon API 점검 중입니다."
    const val DATA_PREPARING = "데이터 준비 중입니다. 잠시 후 다시 시도해주세요."
    const val NO_RECORD = "2023년 12월 21일 이후의 접속 기록이 없습니다."
    const val INVALID_API_KEY = "유효하지 않은 API Key입니다. /키등록 명령어를 통해 다시 등록해주세요."
    const val NOT_OWN_CHARACTER = "자신의 계정에 속한 캐릭터만 조회가 가능합니다."
    const val API_KEY_REQUIRED = "/키등록 명령어를 통해 NEXON Open API에서 발급받은 본인의 API_KEY를 등록해주세요.\n사용법: 키등록 [API_KEY]"
    const val NO_HISTORY = "히스토리 데이터가 없습니다."
    const val NO_RECENT_EXP = "최근 경험치 데이터가 없습니다."
    const val NO_SYMBOL = "심볼 정보가 없습니다."

    private val ERRORS = setOf(
        API_ERROR,
        CHECK_NICKNAME,
        TOO_MANY_REQUESTS,
        SERVER_ERROR,
        MAINTENANCE,
        API_MAINTENANCE,
        DATA_PREPARING,
        NO_RECORD,
        INVALID_API_KEY,
        NOT_OWN_CHARACTER
    )

    fun isError(message: String): Boolean = message in ERRORS
}
