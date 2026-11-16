package com.omnix.assistant.agent.tools.files

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.omnix.assistant.agent.core.OmniTool
import com.omnix.assistant.agent.core.ToolCategory
import com.omnix.assistant.agent.model.ToolExecutionResult
import com.omnix.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ассистент отдаёт пользователю файл: создаёт его из текста и открывает системное
 * меню «Поделиться», откуда файл можно сохранить или переслать в мессенджер.
 */
@Singleton
class CreateFileTool @Inject constructor(
    @ApplicationContext private val context: Context
) : OmniTool {

    override val toolId: String = "files.create"
    override val description: String =
        "Создаёт текстовый файл (txt, md, csv, json, html) из готового текста и отдаёт пользователю через меню «Поделиться»"
    override val category: ToolCategory = ToolCategory.COMMUNICATION
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val mayDiscloseUserContentExternally: Boolean = true
    override val requiresForeground: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("filename") {
                put("type", "string")
                put("description", "Имя файла с расширением, например plan.md или table.csv")
            }
            putJsonObject("content") {
                put("type", "string")
                put("description", "Полный текст файла")
            }
        }
        put("required", buildJsonArray { add("filename"); add("content") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val content = arguments["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (content.isBlank()) {
            return ToolExecutionResult.failure("Пустое содержимое файла", "MISSING_CONTENT")
        }
        if (content.length > MAX_CONTENT_CHARS) {
            return ToolExecutionResult.failure("Файл слишком большой", "CONTENT_TOO_LARGE")
        }
        val name = safeFileName(arguments["filename"]?.jsonPrimitive?.contentOrNull)
        return try {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            removeOldFiles(dir)
            val file = File(dir, name)
            file.writeText(content, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files-provider", file)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substringAfterLast('.', "")) ?: "text/plain"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "OMNIX: $name").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            ToolExecutionResult.success("Файл «$name» готов, открываю меню «Поделиться»", actionRequiresUser = true)
        } catch (e: IOException) {
            ToolExecutionResult.failure("Не удалось создать файл: ${e.localizedMessage}", "FILE_WRITE_ERROR")
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult.failure("Не удалось выдать файл: ${e.localizedMessage}", "FILE_URI_ERROR")
        }
    }

    private fun removeOldFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - ONE_DAY_MS
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    companion object {
        const val SHARE_DIR = "shared"
        private const val MAX_CONTENT_CHARS = 200_000
        private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
        private val ALLOWED_EXTENSIONS = setOf("txt", "md", "csv", "json", "html", "xml", "log", "yaml", "yml")

        /** Имя без путей и служебных символов; расширение только из списка, иначе .txt. */
        fun safeFileName(raw: String?): String {
            val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                .trim().trim('.')
            val stem = base.substringBeforeLast('.', base).trim().ifEmpty { "omnix" }.take(60)
            val ext = base.substringAfterLast('.', "").lowercase()
            return "$stem.${if (ext in ALLOWED_EXTENSIONS) ext else "txt"}"
        }
    }
}
