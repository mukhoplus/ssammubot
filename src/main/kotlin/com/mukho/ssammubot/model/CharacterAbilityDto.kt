package com.mukho.ssammubot.model

data class CharacterAbilityDto(
    val date: String?,
    val ability_grade: String,
    val ability_info: List<AbilityInfoDto>,
    val remain_fame: Number,
    val preset_no: Number,
    val ability_preset_1: AbilityPresetInfoDto,
    val ability_preset_2: AbilityPresetInfoDto,
    val ability_preset_3: AbilityPresetInfoDto
)
