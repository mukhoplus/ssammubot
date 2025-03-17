package com.mukho.ssammubot.utils

class JoaUtil {

    companion object {
        private val veryGoods = setOf("패파", "비숍", "불독", "플위", "나로", "아델", "신궁")
        private val goods = setOf("썬콜", "캡틴", "보마", "팔라", "듀블", "윈브", "나워", "스커", "제논", "라라" )
        private val questions = setOf("아란", "메르", "아크", "미하일", "소마", "배메", "루미")

        fun getJoa(word: String): String {
            if (veryGoods.any { word.contains(it) }) return "완전조아!"
            else if (goods.any { word.contains(it) }) return "조아!"
            else if (questions.any { word.contains(it) }) return "?"
            else if (word == "와헌") return "쌀쌀쌀!"
            return ""
        }
    }
}