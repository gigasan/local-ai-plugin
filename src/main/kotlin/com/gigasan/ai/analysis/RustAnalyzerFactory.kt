package com.gigasan.ai.analysis

import com.intellij.openapi.project.Project

class RustAnalyzerFactory : ProjectAnalyzerFactory {
    override fun create(project: Project): ProjectAnalyzer {
        return RustProjectAnalyzer()
    }
}