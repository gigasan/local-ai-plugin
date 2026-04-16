package com.gigasan.ai.pipeline

import com.intellij.openapi.vfs.VirtualFile

object SelectStage {

    fun accept(file: VirtualFile, config: PipelineConfig): Boolean {

        if (file.isDirectory) return false

        // 1. фильтр по расширению
        if (config.allowedExtensions.isNotEmpty()) {
            val ext = file.extension?.lowercase() ?: return false
            if (ext !in config.allowedExtensions) return false
        }

        // 2. фильтр по размеру
        config.maxFileSize?.let {
            if (file.length > it) return false
        }

        return true
    }
}