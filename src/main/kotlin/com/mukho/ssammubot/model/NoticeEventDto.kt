package com.mukho.ssammubot.model

data class NoticeEventListDto(
    val event_notice: List<NoticeEventItemDto>? = emptyList()
)

data class NoticeEventItemDto(
    val title: String? = null,
    val url: String? = null,
    val notice_id: Long? = null,
    val date: String? = null,
    val date_event_start: String? = null,
    val date_event_end: String? = null
)

data class NoticeEventDetailDto(
    val title: String? = null,
    val url: String? = null,
    val contents: String? = null,
    val date: String? = null,
    val date_event_start: String? = null,
    val date_event_end: String? = null
)
