package com.mukho.ssammubot.utils

class LottoUtil {
    companion object {
        fun generateLottoNumbers(): String {
            return (1..45).shuffled().take(6).sorted().joinToString(", ")
        }
    }
}
