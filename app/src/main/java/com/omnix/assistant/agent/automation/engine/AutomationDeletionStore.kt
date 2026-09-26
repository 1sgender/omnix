package com.omnix.assistant.agent.automation.engine

import android.content.Context
import com.omnix.assistant.agent.automation.entity.AutomationEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Память об удалённых ПОЛЬЗОВАТЕЛЕМ правилах (gap-аудит 2026-09-26, блок
 * «Автоматизации», проверка №8).
 *
 * Проблема: initDefaultAutomations() восстанавливает дефолтные правила при
 * каждом старте, если их нет в БД — «удалённое» дефолтное правило ожило бы
 * при следующем системном событии. Удаление из UI обязано переживать
 * реинициализацию: удалённые ruleId маркируются здесь и исключаются из
 * дефолтов НАВСЕГДА (вернуть можно только пересозданием правила).
 *
 * Хранение — SharedPreferences (как у AutomationScheduleManager): это не
 * пользовательская настройка с потоком изменений, а плоский набор маркеров.
 */
interface AutomationDeletionStore {
    fun markDeleted(ruleId: String)
    fun deletedRuleIds(): Set<String>
}

@Singleton
class PreferencesAutomationDeletionStore @Inject constructor(
    @ApplicationContext context: Context
) : AutomationDeletionStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun markDeleted(ruleId: String) {
        prefs.edit().putStringSet(KEY, deletedRuleIds() + ruleId).apply()
    }

    override fun deletedRuleIds(): Set<String> =
        prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    private companion object {
        const val PREFS = "automation_user_deletions"
        const val KEY = "deleted_rule_ids"
    }
}

/**
 * Чистая фильтрация дефолтов (JVM-тест): из кандидатов на инициализацию
 * выбрасываются правила, помеченные удалёнными. Отдельная функция, а не метод
 * движка, чтобы логику можно было проверить без Android-зависимостей.
 */
fun survivingDefaults(
    defaults: List<AutomationEntity>,
    deletedRuleIds: Set<String>
): List<AutomationEntity> = defaults.filter { it.ruleId !in deletedRuleIds }

/** Feature-local binding: движок и ViewModel знают только интерфейс. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AutomationDeletionBindingModule {
    @Binds
    @Singleton
    abstract fun bindAutomationDeletionStore(
        impl: PreferencesAutomationDeletionStore
    ): AutomationDeletionStore
}
