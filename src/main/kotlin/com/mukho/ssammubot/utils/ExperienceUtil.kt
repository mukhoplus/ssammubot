package com.mukho.ssammubot.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.ClassPathResource

object ExperienceUtil {
    // 레벨별 필요 경험치 (0-300, 인덱스 0은 사용하지 않음)
    private val LEVEL_EXP_TABLE: LongArray = run {
        try {
            val mapper = ObjectMapper()
            val resource = ClassPathResource("data/experience-table.json")
            val jsonNode: JsonNode = mapper.readTree(resource.inputStream)
            val expArray = jsonNode.get("expTable")
            
            LongArray(301) { index ->
                if (index == 0) 0L else expArray.get(index - 1).asLong()
            }
        } catch (e: Exception) {
            // 파일 로드 실패 시 기본값 (실제 운영에서는 로깅 필요)
            LongArray(301) { 0L }
        }
    }

    // 레벨별 누적 경험치 (미리 계산된 값 사용)
    private val CUMULATIVE_EXP_TABLE = run {
        try {
            val mapper = ObjectMapper()
            val resource = ClassPathResource("data/experience-prefixsum-table.json")
            val jsonNode: JsonNode = mapper.readTree(resource.inputStream)
            val cumulativeArray = jsonNode.get("cumulativeExpTable")
            
            LongArray(301) { index ->
                cumulativeArray.get(index).asLong()
            }
        } catch (e: Exception) {
            // 파일 로드 실패 시 실시간 계산
            val cumulative = LongArray(301)
            for (i in 1..300) {
                cumulative[i] = cumulative[i - 1] + LEVEL_EXP_TABLE[i]
            }
            cumulative
        }
    }

    /**
     * 현재 레벨/실제 경험치에서 다음 레벨까지 필요한 경험치 계산
     */
    fun getExpToNextLevel(currentLevel: Int, currentActualExp: Long): Long {
        if (currentLevel >= 300) return 0L
        val currentTotalExp = CUMULATIVE_EXP_TABLE[currentLevel] + currentActualExp
        val nextLevelTotalExp = CUMULATIVE_EXP_TABLE[currentLevel + 1]

        return nextLevelTotalExp - currentTotalExp
    }

    /**
     * 현재 레벨/실제 경험치에서 목표 레벨까지 필요한 총 경험치 계산
     */
    fun getExpToTargetLevel(currentLevel: Int, currentActualExp: Long, targetLevel: Int): Long {
        if (currentLevel >= targetLevel || targetLevel > 300) return 0L
        
        val currentTotalExp = CUMULATIVE_EXP_TABLE[currentLevel] + currentActualExp
        val targetTotalExp = CUMULATIVE_EXP_TABLE[targetLevel]
        
        return targetTotalExp - currentTotalExp
    }

    /**
     * 평균 경험치 획득량 계산 (실제 경험치 기준, 첫/마지막 데이터만 사용)
     */
    fun calculateAverageExpGain(dailyData: List<Pair<Int, Long>>): Long {
        if (dailyData.isEmpty()) return 0L

        val first = dailyData.first()
        val last = dailyData.last()

        val firstTotal = if (dailyData.size == 1) {
            0
        } else {
            CUMULATIVE_EXP_TABLE[first.first] + first.second
        }
        val lastTotal = CUMULATIVE_EXP_TABLE[last.first] + last.second
        val totalGain = lastTotal - firstTotal
        val days = if (dailyData.size == 1) {
            1
        } else {
           dailyData.size - 1
        }

        return if (totalGain > 0) totalGain / days else 0L
    }

    /**
     * 레벨업 예상 날짜 계산
     */
    fun calculateLevelUpEstimate(
        currentLevel: Int,
        currentExp: Long,
        targetLevel: Int,
        dailyExpGain: Long
    ): String {
        // 300레벨 체크
        if (currentLevel >= 300) {
            return "만렙입니다."
        }
        
        if (targetLevel > 300) {
            return "목표 레벨이 300을 초과합니다."
        }
        
        if (targetLevel <= currentLevel) {
            return "목표 레벨이 현재 레벨보다 낮거나 같습니다."
        }
        
        // 일일 경험치 획득량 체크
        if (dailyExpGain <= 0) {
            return "계산할 수 없습니다."
        }
        
        val requiredExp = getExpToTargetLevel(currentLevel, currentExp, targetLevel)
        val estimatedDays = (requiredExp.toDouble() / dailyExpGain).toInt()
        
        return "${estimatedDays}일 후 예상"
    }
}
