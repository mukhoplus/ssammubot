package com.mukho.ssammubot.model

data class CharacterSchedulerDto(
    val date: String?,
    val character_name: String?,
    val world_name: String?,
    val character_level: Number?,
    val character_class: String?,
    val daily_contents: List<SchedulerContentDto>?,
    val weekly_contents: List<SchedulerContentDto>?,
    val boss_contents: List<SchedulerBossDto>?,
    val weekly_boss_clear_count: Number?,
    val weekly_boss_clear_limit_count: Number?
)

data class SchedulerContentDto(
    val content_name: String?,
    val type: String?,
    val registration_flag: String?,
    val now_count: Number?,
    val max_count: Number?,
    val quest_state: String?
)

data class SchedulerBossDto(
    val content_name: String?,
    val difficulty: String?,
    val cycle: String?,
    val list_order_no: Number?,
    val registration_flag: String?,
    val complete_flag: String?
)
