package com.mukho.ssammubot.model

import com.fasterxml.jackson.annotation.JsonProperty

data class CharacterStatDto(
    val date: String?,
    @JsonProperty("character_class")
    val character_class: String,
    @JsonProperty("final_stat")
    val final_stat: List<FinalStatDto>,
    @JsonProperty("remain_ap")
    val remain_ap: Number
)
