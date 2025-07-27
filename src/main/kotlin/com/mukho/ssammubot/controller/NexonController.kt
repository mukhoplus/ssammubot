package com.mukho.ssammubot.controller

import com.mukho.ssammubot.model.ResponseDto
import com.mukho.ssammubot.service.NexonService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/nexon")
class NexonController(private val nexonService: NexonService) {

    @GetMapping("/scouter")
    fun scouter(@RequestParam characterName: String): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(nexonService.scouter(characterName))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/info")
    fun info(@RequestParam characterName: String): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(nexonService.info(characterName))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/history")
    fun history(@RequestParam characterName: String): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(nexonService.history(characterName))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/symbol")
    fun symbol(@RequestParam characterName: String): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(nexonService.symbol(characterName))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }
}