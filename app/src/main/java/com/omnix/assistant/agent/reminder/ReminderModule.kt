package com.omnix.assistant.agent.reminder

import com.omnix.assistant.data.local.OmnixDatabase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReminderBindingsModule {
    @Binds
    abstract fun bindReminderAlarms(impl: AndroidReminderAlarms): ReminderAlarms

    @Binds
    abstract fun bindReminderActions(impl: ReminderManager): ReminderActions
}

@Module
@InstallIn(SingletonComponent::class)
object ReminderDatabaseModule {
    @Provides
    @Singleton
    fun provideReminderDao(database: OmnixDatabase): ReminderDao = database.reminderDao()
}
