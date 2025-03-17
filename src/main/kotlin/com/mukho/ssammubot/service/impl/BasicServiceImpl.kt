package com.mukho.ssammubot.service.impl

import com.mukho.ssammubot.model.ResponseDto
import com.mukho.ssammubot.service.BasicService
import com.mukho.ssammubot.utils.JoaUtil
import org.springframework.stereotype.Service

@Service("basicService")
class BasicServiceImpl: BasicService {

    override fun help(): ResponseDto {
        val message: String = """
            ★★★ 명령어 모음 ★★★
            모든 한글 명령어는 초성을 지원합니다.
            
            /도움말 : 모든 명령어 출력
            /쌈무 : 서버 상태를 확인
            /환산 [유저명] : 환산 링크
            /뭐먹지 : 랜덤 메뉴 추천
            /vs [A] [B] ... : 랜덤 선택
            /직업추천 : 랜덤 직업 추천
            /정보 [유저명] : 유저 정보 출력
            /경험치히스토리 [유저명] : 경험치 히스토리 출력
        """.trimIndent()

        return ResponseDto(message)
    }

    override fun ssammu(): ResponseDto {
        val message = "반갑다오."

        return ResponseDto(message)
    }

    override fun joa(text: String): ResponseDto {
        val message = JoaUtil.getJoa(text);

        return ResponseDto(message)
    }
}