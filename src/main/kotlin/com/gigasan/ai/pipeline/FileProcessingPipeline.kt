package com.gigasan.ai.pipeline

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 *  ```
 * FileProcessingPipeline
 * ├── FilterStage      (жёсткие исключения)
 * ├── SelectStage      (условия выбора)
 * ├── ScoringStage     (оценка)
 * └── ResponseResult
 * ```
 *```
 * val config = PipelineConfig(
 *     allowedExtensions = setOf("kt", "java"),
 *     maxFileSize = if (sizeCheckbox.isSelected) sizeField.text.toLong() else null,
 *     onlyChanged = changedCheckbox.isSelected
 * )
 *
 * val files = FileProcessingPipeline.process(project, root, config)
 *```
 */
object FileProcessingPipeline {

    fun process(
        project: Project,
        root: VirtualFile,
        config: PipelineConfig
    ): List<VirtualFile> {

        val files = if (config.onlyChanged) {
            GitDiffProvider.getChangedFiles(project)
        } else {
            collectAllFiles(root)
        }

        return files
            .asSequence()
            .filter { FilterStage.accept(project, it) }   // 1
            .filter { SelectStage.accept(it, config) }    // 2
            .sortedByDescending { ScoringStage.score(project, it) } // 3
            .toList()
    }

    private fun collectAllFiles(root: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()

        fun walk(file: VirtualFile) {
            if (!file.isValid) return

            if (!file.isDirectory) {
                result.add(file)
                return
            }

            file.children.forEach { walk(it) }
        }

        walk(root)
        return result
    }
}