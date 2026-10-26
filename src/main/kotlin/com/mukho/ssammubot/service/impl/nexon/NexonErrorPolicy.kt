package com.mukho.ssammubot.service.impl.nexon

internal data class NexonErrorPolicy(
    val codeMessages: Map<String, String> = emptyMap(),
    val forbiddenMessage: String = NexonMessage.API_ERROR,
    val fallback400: String = NexonMessage.NO_RECORD,
) {
    companion object {
        val DEFAULT = NexonErrorPolicy()

        val USER_API_KEY = NexonErrorPolicy(
            codeMessages = mapOf(
                "OPENAPI00004" to NexonMessage.NOT_OWN_CHARACTER,
                "OPENAPI00005" to NexonMessage.INVALID_API_KEY,
            ),
            forbiddenMessage = NexonMessage.INVALID_API_KEY,
        )

        val OCID = NexonErrorPolicy(
            fallback400 = NexonMessage.CHECK_NICKNAME,
        )
    }
}
