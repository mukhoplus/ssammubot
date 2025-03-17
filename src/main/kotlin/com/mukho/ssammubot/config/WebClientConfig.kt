package com.mukho.ssammubot.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig(
    @Value("\${nexon.api.key}") private val apiKey: String
) {

    @Bean
    fun webClient(): WebClient {
        return WebClient.builder()
            .baseUrl("https://open.api.nexon.com/maplestory/v1")
            .defaultHeader("accept", "application/json")
            .defaultHeader("x-nxopen-api-key", "$apiKey")
            .build()
    }
}