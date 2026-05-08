package com.gigasan.ai.config.storage

import com.intellij.util.messages.Topic

interface SettingsChangeListener {
    companion object {
        @Topic.AppLevel // Глобальная тема для настроек уровня приложения
        val TOPIC = Topic.create("Plugin Settings Changed", SettingsChangeListener::class.java)
    }
    fun settingsChanged()
}