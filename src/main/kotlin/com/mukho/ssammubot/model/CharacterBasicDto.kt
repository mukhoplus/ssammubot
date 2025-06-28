package com.mukho.ssammubot.model

data class CharacterBasicDto(
    val date: String?,
    val character_name: String,
    val world_name: String,
    val character_gender: String,
    val character_class: String,
    val character_class_level: String,
    val character_level: Number,
    val character_exp: Number,
    val character_exp_rate: String,
    val character_guild_name: String?,
    val character_image: String,
    val character_date_create: String,
    val access_flag: String,
    val liberation_quest_clear_flag: String
)
