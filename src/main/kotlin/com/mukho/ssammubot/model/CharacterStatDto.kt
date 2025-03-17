package com.mukho.ssammubot.model

data class CharacterStatDto(
    val date: String?,
    val character_class: String,
    val final_stat: List<FinalStatDto>,
    val remain_ap: Number
)
