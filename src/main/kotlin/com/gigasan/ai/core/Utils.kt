package com.gigasan.ai.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange


fun projectHasKotlinSource(project: Project): Boolean {
    var found = false

    ProjectRootManager.getInstance(project)
        .fileIndex
        .iterateContent { file ->
            if (file.extension == "kt") {
                found = true
                false // остановить обход
            } else {
                true // продолжить
            }
        }

    return found
}

fun TextRange.toIntRange(): IntRange = IntRange(this.startOffset, this.endOffset)