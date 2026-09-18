package com.omnix.server.admin

import com.omnix.server.http.HttpRequestContext
import com.omnix.server.http.HttpResponseContext

/**
 * OMNIX Control Plane — SPA (React, собирается из web/admin/ через Vite,
 * вывод коммитится в server/src/main/resources/web/admin/ — сборка сервера
 * не требует Node).
 *
 * Маршруты:
 *  - `/admin`, `/admin/`, `/admin/<client-route>`  → index.html (no-store);
 *  - `/admin/assets/<file>`                        → файл из classpath
 *    (Cache-Control: immutable — Vite хэширует имена);
 *  - `/v1/admin/ui/…` (старые ссылки)          → 301 на `/admin`
 *    (совместимость со старыми ссылками server-rendered панели);
 *  - прочее → null (не наш маршрут).
 *
 * Безопасность:
 *  - только текстовые ассеты (html/js/css/svg/json/map) — тело ответа
 *    String, бинарные типы в SPA нет by design;
 *  - path traversal отсекается (никаких `..` и абсолютных путей);
 *  - unknown путь с расширением → 404, без расширения → index.html
 *    (клиентский роутинг).
 */
class AdminSpaHandler {

    private data class StaticAsset(val content: String, val contentType: String, val cache: String)

    fun handle(request: HttpRequestContext): HttpResponseContext? {
        val path = request.path.substringBefore('?')

        // Старые ссылки server-rendered UI → новая панель.
        if (path.startsWith("/v1/admin/ui")) {
            return HttpResponseContext(
                301, "",
                mapOf("Location" to "/admin", "Cache-Control" to "no-store")
            )
        }
        if (!path.startsWith("/admin")) return null
        if (request.method != "GET" && request.method != "HEAD") {
            return HttpResponseContext(405, "{\"error\":\"method_not_allowed\"}")
        }

        val sub = path.removePrefix("/admin").trim('/')
        if (sub.contains("..") || sub.startsWith("/") || sub.contains('\\')) {
            return HttpResponseContext(404, "{\"error\":\"not_found\"}")
        }

        val asset = resolve(sub) ?: return HttpResponseContext(404, "{\"error\":\"not_found\"}")
        return HttpResponseContext(200, asset.content, mapOf("Content-Type" to asset.contentType, "Cache-Control" to asset.cache))
    }

    /** sub == "" → index; хэшируемый ассет → файл; клиентский роут → index. */
    private fun resolve(sub: String): StaticAsset? {
        val target = when {
            sub.isEmpty() -> "index.html"
            sub.startsWith("assets/") -> sub
            // Путь с расширением, которого нет на диске → честный 404
            // (защита от скана), без расширения — клиентский роутинг SPA.
            sub.substringAfterLast('/').contains('.') -> sub
            else -> "index.html"
        }
        val resource = AdminSpaHandler::class.java.classLoader
            .getResourceAsStream("web/admin/$target".replace("//", "/"))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return null
        val type = when (target.substringAfterLast('.', "")) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "json" -> "application/json; charset=utf-8"
            "map" -> "application/json; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
        val cache = if (target == "index.html") "no-store" else "public, max-age=31536000, immutable"
        return StaticAsset(resource, type, cache)
    }
}
