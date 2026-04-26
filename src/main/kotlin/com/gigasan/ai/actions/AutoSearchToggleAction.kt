package com.gigasan.ai.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.icons.AllIcons

class AutoSearchToggleAction(
    private val onStateChanged: (Boolean) -> Unit
) : ToggleAction("Auto-search on Selection", "Enable/disable automatic search when text is selected", AllIcons.Actions.Search) {

    private var isSelected = false

    override fun isSelected(e: AnActionEvent): Boolean = isSelected

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        isSelected = state
        onStateChanged(state)
    }
}