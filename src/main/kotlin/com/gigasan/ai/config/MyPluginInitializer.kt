package com.gigasan.ai.config

import com.intellij.ide.AppLifecycleListener

class MyPluginInitializer : AppLifecycleListener {
    override fun appFrameCreated(commandLineArgs: List<String>) {
        // Устанавливаем свойства ДО того, как JCEF проснется
        System.setProperty("ide.browser.jcef.gpu.enabled", "true")
        System.setProperty("ide.browser.jcef.contextMenu.devTools.enabled", "true")

        // ide.browser.jcef.command.line.args
        // --force-device-scale-factor=1.5
        // --disable-gpu-vsync
        // --disable-features=PerformanceMonitor --log-severity=disable
        // -Dide.browser.jcef.log.level=info - включить лог jcef
    }
}