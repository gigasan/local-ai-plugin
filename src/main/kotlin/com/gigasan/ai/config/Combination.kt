package com.gigasan.ai.config

enum class Action { ACTION_A, ACTION_B }

enum class Performer(
    val config: Map<Action, String>
) {
    WORKER_ONE(
        mapOf(
            Action.ACTION_A to "Config 1A",
            Action.ACTION_B to "Config 1B"
        )
    ),
    WORKER_TWO(
        mapOf(
            Action.ACTION_A to "Config 2A",
            Action.ACTION_B to "Config 2B"
        )
    );

    fun getSettings(action: Action) = config[action] ?: "Default"
}