package com.gigasan.ai.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile

object GitDiffProvider {

    fun getChangedFiles(project: Project): List<VirtualFile> {
        val manager = ChangeListManager.getInstance(project)

        return manager.allChanges
            .mapNotNull { it.virtualFile }
            .distinct()
    }
}