package com.mukho.ssammubot.scheduler

import com.mukho.ssammubot.service.NexonService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class SundayMapleScheduler(
    private val nexonService: NexonService
) {
    private val logger = LoggerFactory.getLogger(SundayMapleScheduler::class.java)

    @Scheduled(cron = "0/10 0 10 * * FRI", zone = "Asia/Seoul")
    fun preloadSundayMapleNotice() {
        try {
            val cached = nexonService.preloadSundayMaple()
            if (cached) {
                logger.debug("Sunday Maple notice cache is ready.")
            }
        } catch (e: Exception) {
            logger.error("Failed to preload Sunday Maple notice: ${e.message}", e)
        }
    }
}
