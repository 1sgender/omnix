package com.omnix.server.clip

import com.omnix.server.admin.AdminAuditLog
import com.omnix.server.admin.AdminAuthResult
import com.omnix.server.admin.AdminAuthService
import com.omnix.server.admin.AdminPermission
import com.omnix.server.admin.AdminPrincipal
import com.omnix.server.admin.AdminRbac
import com.omnix.server.auth.Authenticator
import com.omnix.server.http.HttpRequestContext
import com.omnix.server.http.HttpResponseContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Control Plane: экран «Наушники» (реестр пар OMNIX Clip и каталог моделей, V012).
 *
 * Та же схема, что у AdminHttpHandler: admin-сессия (или legacy static
 * ADMIN-токен) → RBAC на backend → действие → audit. Просмотр —
 * DEVICES_READ, любые изменения — DEVICES_REVOKE (ADMIN/SUPER_ADMIN).
 *
 * BT-адрес наружу только маскированный (`AA:••:••:••:EE:FF`): полный адрес
 * нужен лишь серверу для поиска пары.
 *
 * Подключается в Main.kt ДО AdminHttpHandler: тот отвечает 404 на любой
 * неизвестный `/v1/admin/…`.
 */
class ClipAdminHttpHandler(
    private val auth: AdminAuthService,
    private val staticAuthenticator: Authenticator?,
    private val registry: ClipPairRegistry,
    private val audit: AdminAuditLog,
    private val json: Json = Json
) {
    companion object {
        const val PREFIX = "/v1/admin/earbuds"
        private const val MAX_BULK_LINES = 500
    }

    private class BadRequest(message: String) : Exception(message)

    fun handle(request: HttpRequestContext): HttpResponseContext? {
        val path = request.path.substringBefore('?')
        if (path != PREFIX && !path.startsWith("$PREFIX/")) return null
        val parts = path.removePrefix(PREFIX).trim('/').split('/').filter { it.isNotEmpty() }
        val principal = when (val result = auth.authenticate(request.authorizationHeader, staticAuthenticator)) {
            is AdminAuthResult.Success -> result.principal
            AdminAuthResult.Unauthenticated -> return plain(401, errorBody("unauthenticated"))
        }
        val method = request.method
        val route: Pair<AdminPermission, () -> HttpResponseContext> = when {
            parts.isEmpty() && method == "GET" ->
                AdminPermission.DEVICES_READ to { list(request) }
            parts.isEmpty() && method == "POST" ->
                AdminPermission.DEVICES_REVOKE to { registerOne(principal, request) }
            parts == listOf("bulk") && method == "POST" ->
                AdminPermission.DEVICES_REVOKE to { registerBulk(principal, request) }
            parts == listOf("models") && method == "GET" ->
                AdminPermission.DEVICES_READ to { models() }
            parts == listOf("models") && method == "POST" ->
                AdminPermission.DEVICES_REVOKE to { saveModel(principal, request, null) }
            parts.size == 2 && parts[0] == "models" && method == "PUT" ->
                AdminPermission.DEVICES_REVOKE to { saveModel(principal, request, parts[1]) }
            parts.size == 2 && parts[1] == "unbind" && method == "POST" ->
                AdminPermission.DEVICES_REVOKE to { transition(principal, request, parts[0], unbind = true) }
            parts.size == 2 && parts[1] == "revoke" && method == "POST" ->
                AdminPermission.DEVICES_REVOKE to { transition(principal, request, parts[0], unbind = false) }
            else -> return plain(404, errorBody("not_found"))
        }
        if (!AdminRbac.can(principal.role, route.first)) {
            return plain(403, errorBody("role ${principal.role} lacks ${route.first}"))
        }
        return try {
            route.second()
        } catch (e: BadRequest) {
            plain(400, errorBody(e.message ?: "invalid_request"))
        }
    }

    /* ------------------------------------------------------------ registry */

    private fun list(request: HttpRequestContext): HttpResponseContext {
        val status = queryParam(request, "status")?.let { raw ->
            ClipDeviceStatus.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw BadRequest("unknown status '$raw'")
        }
        val rows = registry.list(status, queryParam(request, "q"), limit = 500)
        val models = registry.listModels(includeHidden = true).associateBy { it.id }
        return plain(
            200,
            buildJsonObject {
                putJsonArray("earbuds") {
                    rows.forEach { add(entryJson(it, models[it.modelId])) }
                }
            }
        )
    }

    private fun registerOne(principal: AdminPrincipal, request: HttpRequestContext): HttpResponseContext {
        val body = bodyJson(request)
        val registration = ClipPairRegistration(
            clipSerial = body.string("clipSerial").orEmpty(),
            btAddress = body.string("btAddress").orEmpty(),
            modelId = body.string("modelId"),
            planId = body.string("planId"),
            btName = body.string("btName")
        )
        return when (val outcome = registry.register(registration)) {
            is ClipRegisterOutcome.Registered -> {
                auditRegistered(principal, request, outcome.entry, "single")
                plain(201, buildJsonObject { put("earbud", entryJson(outcome.entry, null)) })
            }
            else -> plain(registerStatus(outcome), errorBody(registerError(outcome)))
        }
    }

    /**
     * Массовая регистрация: строки «серийник;MAC» (также `,` или таб).
     * Каждая строка — отдельный результат; ошибки одной не отменяют другие.
     */
    private fun registerBulk(principal: AdminPrincipal, request: HttpRequestContext): HttpResponseContext {
        val body = bodyJson(request)
        val lines = body.string("lines").orEmpty().lines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) throw BadRequest("lines are empty")
        if (lines.size > MAX_BULK_LINES) throw BadRequest("too many lines (max $MAX_BULK_LINES)")
        val modelId = body.string("modelId")
        val planId = body.string("planId")
        var registered = 0
        val results = lines.mapIndexed { index, line ->
            val fields = line.split(';', ',', '\t').map(String::trim)
            val outcome = if (fields.size < 2) {
                ClipRegisterOutcome.Invalid("line")
            } else {
                registry.register(
                    ClipPairRegistration(
                        clipSerial = fields[0],
                        btAddress = fields[1],
                        modelId = modelId,
                        planId = planId,
                        btName = fields.getOrNull(2)
                    )
                )
            }
            buildJsonObject {
                put("line", index + 1)
                put("clipSerial", fields.firstOrNull().orEmpty().take(64))
                if (outcome is ClipRegisterOutcome.Registered) {
                    registered++
                    auditRegistered(principal, request, outcome.entry, "bulk")
                    put("ok", true)
                    put("btAddress", BtAddress.mask(outcome.entry.btAddress))
                } else {
                    put("ok", false)
                    put("error", registerError(outcome))
                }
            }
        }
        return plain(
            200,
            buildJsonObject {
                put("registered", registered)
                put("failed", lines.size - registered)
                put("results", JsonArray(results))
            }
        )
    }

    private fun transition(
        principal: AdminPrincipal,
        request: HttpRequestContext,
        rawId: String,
        unbind: Boolean
    ): HttpResponseContext {
        val id = runCatching { UUID.fromString(rawId) }.getOrNull() ?: throw BadRequest("invalid id")
        val reason = bodyJson(request).string("reason")?.trim().orEmpty()
        if (reason.isEmpty()) throw BadRequest("reason is required")
        val outcome = if (unbind) registry.adminUnbind(id, reason) else registry.adminRevoke(id, reason)
        return when (outcome) {
            is ClipAdminActionOutcome.Done -> {
                audit.append(
                    actor = principal.actor,
                    action = if (unbind) "clip.unbind" else "clip.revoke",
                    entityType = "CLIP",
                    entityId = id.toString(),
                    oldValue = auditState(outcome.before),
                    newValue = auditState(outcome.after, reason),
                    remoteAddress = request.remoteAddress,
                    sessionId = principal.sessionId,
                    requestId = null
                )
                plain(200, buildJsonObject { put("earbud", entryJson(outcome.after, null)) })
            }
            ClipAdminActionOutcome.NotFound -> plain(404, errorBody("not_found"))
            ClipAdminActionOutcome.InvalidState -> plain(409, errorBody("invalid_state"))
        }
    }

    /* -------------------------------------------------------------- models */

    private fun models(): HttpResponseContext = plain(
        200,
        buildJsonObject {
            putJsonArray("models") { registry.listModels(includeHidden = true).forEach { add(modelJson(it)) } }
        }
    )

    private fun saveModel(principal: AdminPrincipal, request: HttpRequestContext, pathId: String?): HttpResponseContext {
        val body = bodyJson(request)
        val status = body.string("status")?.let { raw ->
            ClipModelStatus.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: throw BadRequest("unknown model status '$raw'")
        } ?: ClipModelStatus.ACTIVE
        val patterns = (body["btNamePatterns"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
            .orEmpty()
        val command = ClipModelCommand(
            id = pathId ?: body.string("id").orEmpty(),
            displayName = body.string("displayName").orEmpty(),
            colorName = body.string("colorName").orEmpty(),
            colorHex = body.string("colorHex").orEmpty(),
            imageUrl = body.string("imageUrl"),
            btNamePatterns = patterns,
            status = status
        )
        return when (val outcome = registry.saveModel(command, create = pathId == null)) {
            is ClipModelSaveOutcome.Saved -> {
                audit.append(
                    actor = principal.actor,
                    action = if (outcome.previous == null) "clip.model.create" else "clip.model.update",
                    entityType = "CLIP_MODEL",
                    entityId = outcome.model.id,
                    oldValue = outcome.previous?.let { modelJson(it).toString() } ?: "{}",
                    newValue = modelJson(outcome.model).toString(),
                    remoteAddress = request.remoteAddress,
                    sessionId = principal.sessionId,
                    requestId = null
                )
                plain(if (outcome.previous == null) 201 else 200, buildJsonObject { put("model", modelJson(outcome.model)) })
            }
            is ClipModelSaveOutcome.Invalid -> plain(400, errorBody(outcome.reason))
            ClipModelSaveOutcome.AlreadyExists -> plain(409, errorBody("model_exists"))
            ClipModelSaveOutcome.NotFound -> plain(404, errorBody("not_found"))
        }
    }

    /* ------------------------------------------------------------- helpers */

    private fun auditRegistered(
        principal: AdminPrincipal,
        request: HttpRequestContext,
        entry: ClipRegistryEntry,
        source: String
    ) {
        audit.append(
            actor = principal.actor,
            action = "clip.register",
            entityType = "CLIP",
            entityId = entry.id.toString(),
            oldValue = "{}",
            newValue = buildJsonObject {
                put("clipSerial", entry.clipSerial)
                put("btAddress", BtAddress.mask(entry.btAddress))
                put("modelId", entry.modelId)
                put("planId", entry.planId)
                put("source", source)
            }.toString(),
            remoteAddress = request.remoteAddress,
            sessionId = principal.sessionId,
            requestId = null
        )
    }

    private fun auditState(entry: ClipRegistryEntry, reason: String? = null): String = buildJsonObject {
        put("status", entry.status.name)
        put("ownerAccountId", entry.ownerAccountId?.toString())
        if (reason != null) put("reason", reason.take(256))
    }.toString()

    private fun registerStatus(outcome: ClipRegisterOutcome): Int = when (outcome) {
        ClipRegisterOutcome.DuplicateSerial, ClipRegisterOutcome.DuplicateAddress -> 409
        else -> 400
    }

    private fun registerError(outcome: ClipRegisterOutcome): String = when (outcome) {
        is ClipRegisterOutcome.Registered -> "ok"
        is ClipRegisterOutcome.Invalid -> "invalid_${outcome.reason}"
        ClipRegisterOutcome.DuplicateSerial -> "duplicate_serial"
        ClipRegisterOutcome.DuplicateAddress -> "duplicate_address"
        ClipRegisterOutcome.UnknownModel -> "unknown_model"
        ClipRegisterOutcome.UnknownPlan -> "unknown_plan"
    }

    private fun entryJson(entry: ClipRegistryEntry, model: ClipEarbudModel?): JsonObject = buildJsonObject {
        put("id", entry.id.toString())
        put("clipSerial", entry.clipSerial)
        put("btAddress", BtAddress.mask(entry.btAddress))
        put("identityMode", entry.identityMode.name)
        put("status", entry.status.name)
        put("ownerAccountId", entry.ownerAccountId?.toString())
        put("modelId", entry.modelId)
        put("modelName", model?.let { "${it.displayName} · ${it.colorName}" })
        put("planId", entry.planId)
        put("btName", entry.btName)
        put("statusReason", entry.statusReason)
        put("boundAt", entry.boundAt?.toString())
        put("lastSeenAt", entry.lastSeenAt?.toString())
        put("createdAt", entry.createdAt.toString())
    }

    private fun modelJson(model: ClipEarbudModel): JsonObject = buildJsonObject {
        put("id", model.id)
        put("displayName", model.displayName)
        put("colorName", model.colorName)
        put("colorHex", model.colorHex)
        put("imageUrl", model.imageUrl)
        putJsonArray("btNamePatterns") { model.btNamePatterns.forEach { add(JsonPrimitive(it)) } }
        put("status", model.status.name)
        put("createdAt", model.createdAt.toString())
        put("updatedAt", model.updatedAt.toString())
    }

    private fun bodyJson(request: HttpRequestContext): JsonObject =
        runCatching { json.parseToJsonElement(request.body) as? JsonObject }.getOrNull()
            ?: throw BadRequest("invalid_request")

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun queryParam(request: HttpRequestContext, name: String): String? {
        val query = request.rawQuery ?: request.path.substringAfter('?', "").takeIf { request.path.contains('?') }
        return query?.split('&')
            ?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=', "")
            ?.let { runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8) }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
    }

    private fun errorBody(code: String): JsonObject = buildJsonObject { put("error", code) }

    private fun plain(status: Int, body: JsonObject): HttpResponseContext =
        HttpResponseContext(
            status,
            body.toString(),
            mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
        )
}
