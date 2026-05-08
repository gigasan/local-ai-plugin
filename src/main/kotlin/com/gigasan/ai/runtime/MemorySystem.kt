package com.gigasan.ai.runtime

import com.gigasan.ai.ui.chat.TaskData


// core
object MemorySystem {

    private val tasks = mutableListOf<TaskData>()

    fun add(task: TaskData) {
        tasks += task
    }

    fun all(): List<TaskData> = tasks

    fun last(n: Int = 10): List<TaskData> {
        return tasks.takeLast(n)
    }

    fun clear() {
        tasks.clear()
    }
}

// 👉 превращает память в текст для AI
object MemoryContextBuilder {

    fun build(task: TaskData, limit: Int = 5): String {
        val history = MemorySystem
            .last(limit)
            .filter { it.answer.isNotBlank() }

        if (history.isEmpty()) return ""

        return buildString {
            append("Previous context:\n")

            history.forEach { t ->
                append("- User: ${t.question}\n")
                append("  Assistant: ${t.answer}\n")
            }

            append("\nCurrent task:\n")
            append(task.question)
        }
    }
}