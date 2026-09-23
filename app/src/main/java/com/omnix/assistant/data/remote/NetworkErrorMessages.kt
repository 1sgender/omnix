package com.omnix.assistant.data.remote

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Конкретные причины сетевых сбоев для сообщений пользователю.
 *
 * Раньше любой IOException, кроме таймаута, показывался безликим
 * «Ошибка сети при обращении к серверу OMNIX» — по нему невозможно
 * отличить выключенный интернет от DNS-блокировки оператора связи от
 * SSL-проблемы. [OmnixApiClient] включает причину из этого маппера в
 * сообщение, которое попадает и в чат, и в голосовую озвучку.
 *
 * Чистый объект без Android-зависимостей — покрывается JVM-тестами.
 */
object NetworkErrorMessages {

    /** Короткая человекочитаемая причина сбоя по типу исключения. */
    fun reasonFor(e: IOException): String = when (e) {
        is SocketTimeoutException -> "сервер не отвечает (таймаут)"
        is UnknownHostException ->
            "сервер не найден — нет интернета, либо DNS этой сети не резолвит домен сервера"
        is ConnectException -> "нет соединения с сервером — проверьте подключение к интернету"
        is SSLException -> "проблема защищённого соединения (SSL)"
        else -> "сетевая ошибка (${e.javaClass.simpleName})"
    }

    /** Полное сообщение об ошибке для показа пользователю. */
    fun messageFor(e: IOException): String =
        "Ошибка сети при обращении к серверу OMNIX: ${reasonFor(e)}."
}
