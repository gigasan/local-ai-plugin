package com.gigasan.localai

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.AsyncProcessIcon
import com.google.gson.JsonParser
import com.intellij.util.ui.FormBuilder
import com.jetbrains.rd.util.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.BorderLayout
import java.awt.CardLayout
import java.net.HttpURLConnection
import javax.swing.*
import kotlin.collections.toTypedArray
import kotlin.text.trim

class PluginSettingsConfigurable : Configurable {
    private val cardLayout = CardLayout()
    private val mainPanel = JPanel(cardLayout)

    // Создаем панели один раз
    private val loadingPanel = JPanel(BorderLayout())
    private val contentPanel = JPanel(BorderLayout())

    private val baseUrl = JTextField()
    private val apiKey = JTextField()

    private val modelComboBox = ComboBox<String>()
    private val modelsList = mutableListOf<Model>()

    private val backends = listOf(
        "LmStudioLegacy",
        "Responses",
        "ChatCompletions",
    )
    private var backendsComboBox = ComboBox(backends.toTypedArray())

    private val chatEndpoints = listOf(
        "/api/v1/chat",
        "/v1/responses",
        "/v1/chat/completions",
        "Custom..."
    )

    private var chatEndpointsComboBox = ComboBox(chatEndpoints.toTypedArray())
    private val chatCustomEndpoint = JTextField()

    private val modelListEndpoints = listOf(
        "/api/v1/models",
        "/v1/models",
        "Custom..."
    )
    private var modelListEndpointComboBox = ComboBox(modelListEndpoints.toTypedArray())

    private val modelListCustomEndpoint = JTextField()

    private val modelListCustomPanel = JPanel(BorderLayout()).apply {
        add(JBLabel("Model list custom endpoint:"), BorderLayout.WEST)
        add(modelListCustomEndpoint, BorderLayout.CENTER)
    }
    private val chatCustomPanel = JPanel(BorderLayout()).apply {
        add(JBLabel("Chat custom endpoint:"), BorderLayout.WEST)
        add(chatCustomEndpoint, BorderLayout.CENTER)
    }

    @Volatile
    private var isLoading = true

    data class Model(val key: String, val displayName: String)

    override fun getDisplayName(): String = "Local AI Settings"

    override fun createComponent(): JComponent {
        mainPanel.removeAll()
        loadingPanel.removeAll()
        contentPanel.removeAll()

        // Настройка панели загрузки
        val loaderIndicator = AsyncProcessIcon("LoadingModelsIcon")
        val loadingBox = Box.createVerticalBox()
        loadingBox.add(Box.createVerticalGlue())
        loadingBox.add(loaderIndicator)
        loadingBox.add(JBLabel("Connecting to Server...", SwingConstants.CENTER))
        loadingBox.add(Box.createVerticalGlue())
        loadingPanel.add(loadingBox, BorderLayout.CENTER)


        modelListEndpointComboBox.selectedIndex = PluginSettings.instance.modelListEndpointIndex
        modelListEndpointComboBox.addItemListener {
            val isCustom = modelListEndpointComboBox.selectedItem == "Custom..."
            modelListCustomPanel.isVisible = isCustom
            modelListCustomPanel.revalidate()
            modelListCustomPanel.repaint()
        }
        modelListCustomPanel.isVisible = modelListEndpointComboBox.selectedItem == "Custom..."

        backendsComboBox.selectedIndex = PluginSettings.instance.backendIndex

        chatEndpointsComboBox.selectedIndex = PluginSettings.instance.chatEndpointIndex
        chatEndpointsComboBox.addItemListener {
            val isCustom = chatEndpointsComboBox.selectedItem == "Custom..."
            chatCustomPanel.isVisible = isCustom
            chatCustomPanel.revalidate()
            chatCustomPanel.repaint()
        }
        chatCustomPanel.isVisible = chatEndpointsComboBox.selectedItem == "Custom..."

        // Настройка контента
        val formPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Base URL:", baseUrl)
            .addLabeledComponent("API Key:", apiKey)
            .addSeparator()
            .addLabeledComponent("Model list endpoint:", modelListEndpointComboBox)
            .addComponent(modelListCustomPanel)
            .addLabeledComponent("Model:", modelComboBox)
            .addSeparator()
            .addLabeledComponent("Chat endpoint:", chatEndpointsComboBox)
            .addComponent(chatCustomPanel)
            .addSeparator()
            .addLabeledComponent("Backend:", backendsComboBox)
            .panel

        val topWrapper = JPanel(BorderLayout())
        topWrapper.add(formPanel, BorderLayout.NORTH)
        contentPanel.add(topWrapper, BorderLayout.CENTER)
        mainPanel.add(loadingPanel, "LOADING")
        mainPanel.add(contentPanel, "CONTENT")
        cardLayout.show(mainPanel, "LOADING")

        loadModelsAsync()
        return mainPanel
    }

    private fun loadModelsAsync() {
        isLoading = true
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = fetchModels()

                SwingUtilities.invokeLater {
                    modelsList.clear()
                    modelsList.addAll(result)

                    modelComboBox.removeAllItems()
                    if (result.isEmpty()) {
                        modelComboBox.addItem("No models found or error")
                    } else {
                        result.forEach { modelComboBox.addItem(it.displayName) }
                    }

                    val savedName = PluginSettings.instance.selectedModelName
                    if (savedName.isNotEmpty()) {
                        modelComboBox.selectedItem = savedName
                    }

                    isLoading = false
                    // ФОРСИРУЕМ переключение и перерисовку
                    cardLayout.show(mainPanel, "CONTENT")
                    mainPanel.revalidate()
                    mainPanel.repaint()
                }
            } catch (e: Exception) {
                // Если всё совсем плохо, покажем ошибку вместо вечной загрузки
                SwingUtilities.invokeLater {
                    isLoading = false
                    cardLayout.show(mainPanel, "CONTENT")
                    modelComboBox.addItem("Error: ${e.message}")
                }
            }
        }
    }


    fun parseLmStudio(json: JsonObject): List<Model> {

        val list = (json["models"] ?: json["data"])
            ?.jsonArray
            ?.mapNotNull { it as? JsonObject }

        return list?.map { obj ->

            println("key")
            val key = obj["key"]?.jsonPrimitive?.content
                ?: obj["id"]?.jsonPrimitive?.content
                ?: "unknown"

            println("selectedVariant")
            val selectedVariant = obj["selected_variant"]?.jsonPrimitive?.content

            println("quantName = ${obj["quantization"]}")
            val quantName = (obj["quantization"] as? JsonObject)
                ?.get("name")
                ?.jsonPrimitive
                ?.content
                ?.lowercase()

            println("normalizedId")
            val normalizedId = when {
                selectedVariant != null -> selectedVariant
                key.contains("@") -> key
                quantName != null -> "$key@$quantName"
                else -> key
            }
            println("Model")
            Model(
                key = normalizedId,
                displayName = buildString {
                    append(obj["display_name"]?.jsonPrimitive?.content ?: key)
                    println("last")
                    if (!normalizedId.contains("@") && quantName != null) {
                        append(" @${quantName.uppercase()}")
                    }
                }
            )
        } ?: emptyList()
    }

    fun parseOpenAI(json: JsonObject): List<Model> {

        val array = (json["models"] ?: json["data"])
            ?.jsonArray
            ?.map { it.jsonObject }

        return array?.map { el ->
            val obj = el.jsonObject

            val key = obj["key"]?.jsonPrimitive?.content
                ?: obj["id"]?.jsonPrimitive?.content
                ?: "unknown"

            val selectedVariant = obj["selected_variant"]?.jsonPrimitive?.content

            val quantName = obj["quantization"]
                ?.jsonObject
                ?.get("name")
                ?.jsonPrimitive
                ?.content
                ?.lowercase()

            val normalizedId = when {
                selectedVariant != null -> selectedVariant
                key.contains("@") -> key
                quantName != null -> "$key@$quantName"
                else -> key
            }

            Model(
                key = normalizedId,
                displayName = buildString {
                    append(obj["display_name"]?.jsonPrimitive?.content ?: key)

                    if (!normalizedId.contains("@") && quantName != null) {
                        append(" @${quantName.uppercase()}")
                    }
                }
            )
        } ?: emptyList()

    }

    fun parseModels(json: JsonObject): List<Model> {
        return when {
            json.containsKey("models") -> parseLmStudio(json)
            json.containsKey("data") -> parseOpenAI(json)
            else -> emptyList()
        }
    }

    private fun fetchModels(): List<Model> {
        val settings = PluginSettings.instance
        val modelListFull: String = when (settings.modelListEndpointIndex) {
            0 -> settings.baseUrl.trimEnd('/') + "/api/v1/models"
            1 -> settings.baseUrl.trimEnd('/') + "/v1/models"
            else  -> settings.baseUrl.trimEnd('/') + settings.modelListEndpoint.trim()
        }

        val url = URI.create(modelListFull).toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        return try {
            connection.inputStream.bufferedReader().use { reader ->
                val response = reader.readText()
                val json = Json.parseToJsonElement(response).jsonObject
                parseModels(json)
            }
        } catch (e: Exception) {
            println("Error fetching models: ${e.message}")
            throw e
        } finally {
            connection.disconnect()
        }
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.instance

        // 1. Проверяем текстовые поля всегда
        if (settings.apiKey != apiKey.text.trim()) return true
        if (settings.baseUrl != baseUrl.text.trim()) return true
        if (settings.backendIndex != backendsComboBox.selectedIndex) return true
        if (settings.chatEndpointIndex != chatEndpointsComboBox.selectedIndex) return true
        if (settings.chatEndpoint != chatCustomEndpoint.text.trim()) return true
        if (settings.modelListEndpointIndex != modelListEndpointComboBox.selectedIndex) return true
        if (settings.modelListEndpoint != modelListCustomEndpoint.text.trim()) return true

        // 2. Если модели ещё не готовы — не трогаем ComboBox
        if (isLoading || modelsList.isEmpty()) return false

        // 3. Проверяем модель
        val index = modelComboBox.selectedIndex
        val selectedKey = modelsList.getOrNull(index)?.key

        return selectedKey != settings.selectedModelKey
    }

    override fun apply() {
        val settings = PluginSettings.instance

        // Сохраняем текстовые поля всегда
        settings.apiKey = apiKey.text.trim()
        settings.baseUrl = baseUrl.text.trim()
        settings.backendIndex = backendsComboBox.selectedIndex
        settings.chatEndpointIndex = chatEndpointsComboBox.selectedIndex
        settings.chatEndpoint = chatCustomEndpoint.text.trim()
        settings.modelListEndpointIndex = modelListEndpointComboBox.selectedIndex
        settings.modelListEndpoint = modelListCustomEndpoint.text.trim()

        // Сохраняем модель только если список загружен
        if (!isLoading && modelsList.isNotEmpty()) {
            val selectedIndex = modelComboBox.selectedIndex
            if (selectedIndex >= 0 && selectedIndex < modelsList.size) {
                settings.selectedModelKey = modelsList[selectedIndex].key
                settings.selectedModelName = modelsList[selectedIndex].displayName
            }
        }
        settings.notifyChange() // ⚡ сигнал об обновлении
    }

    override fun reset() {
        val settings = PluginSettings.instance
        apiKey.text = settings.apiKey
        modelListCustomEndpoint.text = settings.modelListEndpoint.ifBlank { "/v1/models" }
        chatCustomEndpoint.text = settings.chatEndpoint.ifBlank { "/v1/responses" }
        baseUrl.text = settings.baseUrl.ifBlank { "http://127.0.0.1:11434" }

        if (modelsList.isNotEmpty()) {
            val index = modelsList.indexOfFirst {
                it.key == settings.selectedModelKey
            }
            if (index >= 0) {
                modelComboBox.selectedIndex = index
            }
        }
    }
}