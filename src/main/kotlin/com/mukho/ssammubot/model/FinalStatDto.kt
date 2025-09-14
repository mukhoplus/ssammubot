package com.mukho.ssammubot.model

import com.fasterxml.jackson.annotation.JsonProperty

data class FinalStatDto(
    @JsonProperty("stat_name")
    val stat_name: String,
    @JsonProperty("stat_value")
    val stat_value: String?,
)
