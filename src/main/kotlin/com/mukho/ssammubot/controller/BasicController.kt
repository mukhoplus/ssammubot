package com.mukho.ssammubot.controller

import com.mukho.ssammubot.model.ResponseDto
import com.mukho.ssammubot.service.BasicService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/basic")
class BasicController(private val basicService: BasicService) {

    @GetMapping("/help")
    fun help(): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(basicService.help())
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/ssammu")
    fun ssammu(): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(basicService.ssammu())
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/joa")
    fun joa(@RequestParam("text") text: String): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(basicService.joa(text))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/poten")
    fun poten(): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(basicService.poten())
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }
}