package com.gigasan.ai.analysis

import com.intellij.openapi.project.Project

interface ProjectAnalyzerFactory {
    fun create(project: Project): ProjectAnalyzer
}