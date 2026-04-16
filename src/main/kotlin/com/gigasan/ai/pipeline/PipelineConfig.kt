package com.gigasan.ai.pipeline

data class PipelineConfig(
    val allowedExtensions: Set<String> = emptySet(), // ComboBox
    val maxFileSize: Long? = null,                   // CheckBox + input
    val onlyChanged: Boolean = false                 // optional
)
