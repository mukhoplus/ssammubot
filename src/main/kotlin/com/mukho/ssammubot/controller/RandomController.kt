package com.mukho.ssammubot.controller

import com.mukho.ssammubot.model.ResponseDto
import com.mukho.ssammubot.model.VsDto
import com.mukho.ssammubot.service.impl.RandomServiceImpl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/random")
class RandomController(private val randomService: RandomServiceImpl) {

    @GetMapping("/food")
    fun food(): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(randomService.food())
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/class")
    fun classRecommand(): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(randomService.classRecommand())
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }

    @GetMapping("/vs")
    fun vs(@RequestBody vsDto: VsDto): ResponseEntity<ResponseDto> {
        return try {
            ResponseEntity.ok(randomService.vs(vsDto))
        } catch (e: Exception) {
            ResponseEntity.internalServerError().build()
        }
    }
}