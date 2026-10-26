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

    @GetMapping("/abil")
    fun abil(@RequestParam characterName: String): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(nexonService.abil(characterName))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/levelup")
    fun levelUp(@RequestParam characterName: String, @RequestParam targetLevel: Int?): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(nexonService.levelUp(characterName, targetLevel))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/ring")
    fun ring(@RequestParam characterName: String): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(nexonService.ring(characterName))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @PostMapping("/key")
    fun encryptKey(@RequestHeader("x-nxopen-api-key") apiKey: String): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(nexonService.encryptKey(apiKey))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/boss")
    fun boss(
        @RequestParam characterName: String,
        @RequestHeader("x-nxopen-api-key", required = false) apiKey: String?
    ): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(nexonService.boss(characterName, apiKey))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/quest")
    fun quest(
        @RequestParam characterName: String,
        @RequestHeader("x-nxopen-api-key", required = false) apiKey: String?
    ): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(nexonService.quest(characterName, apiKey))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }
}
