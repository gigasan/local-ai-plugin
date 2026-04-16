package com.gigasan.ai.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile

object ScoringStage {

    fun score(project: Project, file: VirtualFile): Int {
        var score = 0

        val name = file.name.lowercase()

        // 🔥 изменённые файлы
        if (isChanged(project, file)) score += 100

        // важные файлы
        if (name.contains("system")) score += 15
        if (name.contains("service")) score += 10
        if (name.contains("controller")) score += 10
        if (name.contains("manager")) score += 8
        if (name.contains("config")) score += 5

        // штрафы
        if (name.contains("test")) score -= 20

        return score
    }

    private fun isChanged(project: Project, file: VirtualFile): Boolean {
        val manager = ChangeListManager.getInstance(project)
        return manager.getChange(file) != null
    }
}