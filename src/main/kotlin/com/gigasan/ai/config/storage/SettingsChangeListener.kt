package com.gigasan.ai.config.storage

import com.intellij.util.messages.Topic

interface SettingsChangeListener {
    companion object {
        @Topic.AppLevel
        val TOPIC = Topic.create("Plugin Settings Changed", SettingsChangeListener::class.java)
    }
    fun settingsChanged()
}