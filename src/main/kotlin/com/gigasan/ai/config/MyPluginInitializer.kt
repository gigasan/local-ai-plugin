package com.gigasan.ai.config

import com.intellij.ide.AppLifecycleListener

class MyPluginInitializer : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        // Устанавливаем свойства ДО того, как JCEF проснется
        System.setProperty("ide.browser.jcef.out-of-process.enabled", "false")
        System.setProperty("ide.browser.jcef.gpu.enabled", "true")
        System.setProperty("ide.browser.jcef.contextMenu.devTools.enabled", "true")
        System.setProperty("ide.browser.jcef.enabled", "true")
    }
}