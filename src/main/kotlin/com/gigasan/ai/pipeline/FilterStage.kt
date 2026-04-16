package com.gigasan.ai.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile

object FilterStage {

    fun accept(project: Project, file: VirtualFile): Boolean {

        // 1. .gitignore + IDE ignore
        if (ChangeListManager.getInstance(project).isIgnoredFile(file)) return false

        // 2. бинарные файлы
        if (file.fileType.isBinary) return false

        // 3. пустые файлы
        if (!file.isDirectory && file.length == 0L) return false

        // 4. мусорные папки
        if (isInternal(file)) return false

        return true
    }

    private fun isInternal(file: VirtualFile): Boolean {
        val name = file.name.lowercase()

        return name in setOf(
            ".git", ".idea", ".gradle",
            "node_modules", "build", "dist", "out", "target"
        )
    }
}