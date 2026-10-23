package com.mukho.ssammubot.model

data class CharacterItemEquipmentDto(
    val date: String?,
    val character_gender: String?,
    val character_class: String?,
    val preset_no: Number?,
    val item_equipment: List<ItemEquipmentDto>?
)

data class ItemEquipmentDto(
    val item_equipment_part: String?,
    val item_equipment_slot: String?,
    val item_name: String?,
    val special_ring_level: Number?
)
