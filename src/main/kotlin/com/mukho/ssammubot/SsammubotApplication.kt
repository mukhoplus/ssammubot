package com.mukho.ssammubot

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class SsammubotApplication

fun main(args: Array<String>) {
	runApplication<SsammubotApplication>(*args)
}
