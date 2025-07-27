package com.mukho.ssammubot.model

data class SymbolDto(
    val symbol_name: String,
    val symbol_icon: String,
    val symbol_description: String,
    val symbol_force: String,
    val symbol_level: Number,
    val symbol_str: String,
    val symbol_dex: String,
    val symbol_int: String,
    val symbol_luk: String,
    val symbol_drop_rate: String,
    val symbol_meso_rate: String,
    val symbol_exp_rate: String,
    val symbol_growth_count: Number,
    val symbol_require_growth_count: Number
)