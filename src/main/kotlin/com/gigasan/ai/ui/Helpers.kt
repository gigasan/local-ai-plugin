package com.gigasan.ai.ui

import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.runtime.AIMetrics
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel

fun showModelInfoDialog(model: Model) {
    val sizeHuman = StringUtil.formatFileSize(model.size)
    val ctxHuman = AIMetrics.formatSize(model.maxContext)

    val reasoningStatus =
        if (model.reasoningOptions.isNullOrEmpty() || model.defaultReasoning == null) {
            "<i style='color:gray'>not supported</i>"
        } else {
            "${model.reasoningOptions.joinToString(", ", "[", "]")} : ${model.defaultReasoning}"
        }

    val descriptionBlock =
        if (model.description.isNotBlank()) {
            """
            <hr/>
            <div>
            • Description: ${model.description}<br/>
            </div>
            """.trimIndent()
        } else {
            ""
        }

    val html = """
        <html>
        <body style='font-family:sans-serif; padding:10px; width:500px;'>
        
        <h2>${model.displayName}</h2>
        
        <table cellpadding='4'>
            <tr><td><b>Max context:</b></td><td>$ctxHuman</td></tr>
            <tr><td><b>Quant:</b></td><td>${model.quant}</td></tr>
            <tr><td><b>Params:</b></td><td>${model.params}</td></tr>
            <tr><td><b>Size:</b></td><td>$sizeHuman</td></tr>
            <tr><td><b>Reasoning:</b></td><td>$reasoningStatus</td></tr>
            <tr><td><b>Tools:</b></td><td>${model.tools}</td></tr>
            <tr><td><b>Format:</b></td><td>${model.format}</td></tr>
            <tr><td><b>Architecture:</b></td><td>${model.arc}</td></tr>
            <tr><td><b>Source:</b></td><td>${model.source}</td></tr>
            <tr><td><b>Key:</b></td><td>${model.key}</td></tr>
        </table>
        $descriptionBlock
        </body>
        </html>
    """.trimIndent()

    val editorPane = JEditorPane("text/html", html).apply {
        isEditable = false
        isOpaque = false
        caretPosition = 0
    }

    val panel = JPanel(BorderLayout()).apply {
        add(JBScrollPane(editorPane), BorderLayout.CENTER)
        preferredSize = java.awt.Dimension(720, 480)
    }

    object : DialogWrapper(true) {

        init {
            title = "Model Information"
            init()
        }

        override fun createCenterPanel(): JComponent {
            return panel
        }

    }.show()
}


fun createTooltipRenderer() = object : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val fullText = value?.toString() ?: ""

        // В самом списке (в выпадающем меню) показываем только первую строку,
        // чтобы комбобокс не раздувался.
        val previewText = fullText.lineSequence().firstOrNull() ?: ""

        val c = super.getListCellRendererComponent(list, previewText, index, isSelected, cellHasFocus) as JComponent

        if (value != null) {
            // Экранируем HTML спецсимволы, чтобы текст не интерпретировался как теги
            val escapedText = fullText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            // Оборачиваем в html и pre для сохранения форматирования
            // Тег <pre> идеально сохраняет табы и переносы
            c.toolTipText = "<html><pre>$escapedText</pre></html>"
        }

        return c
    }
}

fun createModelListRenderer(modelsList: List<Model>) = object : SimpleListCellRenderer<String>() {
    override fun customize(
        list: JList<out String>,
        value: String?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ) {
        text = value ?: ""

        // Отрисовка только для элементов в выпадающем списке (index >= 0)
        if (index >= 0 && value != null) {
            val modelInfo = modelsList.find { it.displayName == value }

            if (modelInfo != null) {
                val sizeHuman = StringUtil.formatFileSize(modelInfo.size)
                val ctxHuman = AIMetrics.formatSize(modelInfo.maxContext)

                val reasoningStatus = if (modelInfo.reasoningOptions.isNullOrEmpty() || modelInfo.defaultReasoning == null) {
                    "<i color='gray'>not supported</i>"
                } else {
                    "${modelInfo.reasoningOptions.joinToString(", ", "[", "]")}: ${modelInfo.defaultReasoning}"
                }

                val descriptionBlock =
                    if (modelInfo.description.isNotBlank()) {
                        """
                        <hr/>
                        • Description: ${modelInfo.description}<br/>
                        """.trimIndent()
                    } else {
                        ""
                    }

                toolTipText = """
                <html>
                    <div style='margin: 5px;'>
                    <b>Model parameters:</b><br/>
                    • Name: ${modelInfo.displayName} <br/>
                    • Max context: $ctxHuman <br/>
                    • Quant: ${modelInfo.quant} <br/>
                    • Params: ${modelInfo.params} <br/>
                    • Size: $sizeHuman <br/>
                    <hr/>
                    • Reasoning: $reasoningStatus <br/>
                    • Tools: ${modelInfo.tools} <br/>
                    • Format: ${modelInfo.format} <br/>
                    • Arc: ${modelInfo.arc} <br/>
                    • Source: ${modelInfo.source} <br/>
                    • Key: ${modelInfo.key} <br/>
                    $descriptionBlock
                    </div>
                </html>
                """.trimIndent()
            }
        } else {
            toolTipText = null
        }
    }
}