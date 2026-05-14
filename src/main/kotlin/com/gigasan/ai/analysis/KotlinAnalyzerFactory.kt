package com.gigasan.ai.analysis

import com.intellij.openapi.project.Project

class KotlinAnalyzerFactory : ProjectAnalyzerFactory {
    override fun create(project: Project): ProjectAnalyzer {
        return KotlinProjectAnalyzer()
    }
}