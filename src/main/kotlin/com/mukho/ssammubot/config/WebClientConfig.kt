package com.mukho.ssammubot.config

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class WebClientConfig(
    @Value("\${nexon.api.key}") private val apiKey: String
) {

    @Bean
    @Primary
    fun webClient(): WebClient {
        return WebClient.builder()
            .baseUrl("https://open.api.nexon.com/maplestory/v1")
            .defaultHeader("accept", "application/json")
            .defaultHeader("x-nxopen-api-key", "$apiKey")
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)
            }
            .build()
    }

    @Bean("imageWebClient")
    fun imageWebClient(): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            .responseTimeout(Duration.ofSeconds(15))

        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)
            }
            .build()
    }

    @Bean("geminiRestClient")
    fun geminiRestClient(): RestClient {
        val jdkHttpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(jdkHttpClient).apply {
            setReadTimeout(Duration.ofSeconds(15))
        }

        return RestClient.builder()
            .baseUrl("https://generativelanguage.googleapis.com")
            .requestFactory(requestFactory)
            .build()
    }
}
