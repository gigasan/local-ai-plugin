package com.gigasan.ai.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
//import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
//import com.intellij.util.ui.FormBuilder
import com.jetbrains.rd.util.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import javax.swing.SwingUtilities
import com.intellij.openapi.project.Project
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.builder.bindItem
import java.util.Locale.getDefault
import com.gigasan.ai.core.JsonFileLogger

//import com.intellij.ui.dsl.builder.collapsibleGroup
//import com.intellij.ui.dsl.builder.comboBox

data class BackendItem(val backend: BackendEngine) {
    override fun toString(): String = backend.displayName  // для старых моделей
}

class PluginSettingsConfigurable(private val project: Project) : BoundConfigurable("Local AI Settings"), JsonFileLogger {
    private val global = PluginSettings.instance.state
    private val settings = ProjectSpecificSettings.getInstance(project)
    private val modelComboBox = ComboBox<String>()
    private val modelsList = mutableListOf<Model>()

    @Volatile
    private var isLoading = true

    private val logger = Logger.getInstance("PluginSettingsConfigurable")

    val availableBackendEndpoints = BackendEndpoints.entries.filter { type ->
        type == BackendEndpoints.LM_STUDIO_ENDPOINT || global.allowedBackendEndpoints.contains(type)
    }

    // Храним модели, чтобы обновлять их динамически
    private val modelsModel = MutableCollectionComboBoxModel<String>()
    private val chatModel = MutableCollectionComboBoxModel<String>()
    private val urlModel = MutableCollectionComboBoxModel<String>()

    private lateinit var modelsCombo: com.intellij.openapi.ui.ComboBox<String>
    private lateinit var chatCombo: com.intellij.openapi.ui.ComboBox<String>
    private lateinit var urlCombo: com.intellij.openapi.ui.ComboBox<String>

    private fun updateDependentCombos(backendEngine: BackendEngine, backendApi: BackendApi) {

        val backendEndpoint = BackendEndpoints.fromId(backendEngine.id, backendApi.id)?: BackendEndpoints.LM_STUDIO_ENDPOINT
        //val currentBackend = BackendEngine.fromId(backendId)
        logger.info("updateDependentCombos $backendEndpoint backend $backendEngine api $backendApi")

        var currentBackendEndpoint = backendEndpoint

        // Если вдруг id не из разрешённых — сразу исправляем
        if (currentBackendEndpoint !in availableBackendEndpoints) {
            currentBackendEndpoint = availableBackendEndpoints.firstOrNull() ?: BackendEndpoints.LM_STUDIO_ENDPOINT
            settings.state.backendEngineId = currentBackendEndpoint.engine.id
            settings.state.backendApiId = currentBackendEndpoint.api.id
            logger.info("Backend fallback: $backendEndpoint → $currentBackendEndpoint")
        }

        // Models
        val newModels = currentBackendEndpoint.defaultModelList
        modelsModel.update(newModels)

        val modelToSelect = newModels.firstOrNull { it == settings.state.modelListEndpointUrl }
            ?: newModels.firstOrNull() ?: ""
        logger.info("updateDependentCombos modelToSelect=${modelToSelect}")
        settings.state.modelListEndpointUrl = modelToSelect
        if (::modelsCombo.isInitialized) {
            modelsCombo.selectedItem = modelToSelect
        }

        // Chat
        //val newChat = currentBackendEndpoint.defaultChat
        val newChat = currentBackendEndpoint.defaultResponses
        chatModel.update(newChat)

        val chatToSelect = newChat.firstOrNull { it == settings.state.chatEndpointUrl }
            ?: newChat.firstOrNull() ?: ""
        logger.info("updateDependentCombos chatToSelect=${chatToSelect}")
        settings.state.chatEndpointUrl = chatToSelect
        if (::chatCombo.isInitialized) {
            chatCombo.selectedItem = chatToSelect
        }

        // URL
        val newUrl = currentBackendEndpoint.engine.defaultHost
        urlModel.update(newUrl)

        val urlToSelect = newUrl.firstOrNull { it == settings.state.baseUrl }
            ?: newChat.firstOrNull() ?: ""
        logger.info("updateDependentCombos urlToSelect=${urlToSelect}")
        settings.state.baseUrl = urlToSelect
        if (::urlCombo.isInitialized) {
            urlCombo.selectedItem = urlToSelect
        }

    }

    override fun getDisplayName(): String = "Local AI Settings"

    override fun createPanel(): DialogPanel = panel {  // ← Kotlin UI DSL Version 2

        //val propertyGraph = PropertyGraph(this.toString())

        val backendModel = MutableCollectionComboBoxModel(availableBackendEndpoints)

        // После создания backendModel и до collapsibleGroup
        updateDependentCombos(BackendEngine.fromId(settings.state.backendEngineId), BackendApi.fromId(settings.state.backendApiId))   // ← инициализируем списки при первом открытии

        collapsibleGroup("Connections") {

            row("Backend:") {

                comboBox(backendModel)
                    // 2. Привязываем не сам объект, а его id (через getter/setter)
                    .bindItem(
                        // ← УМНЫЙ GETTER
                        getter = {
                            val storedEngineId = settings.state.backendEngineId
                            val storedApiId = settings.state.backendApiId
                            // Ищем по настоящему id
                            availableBackendEndpoints.find { it.engine.id == storedEngineId && it.api.id == storedApiId}
                            // Если id больше не разрешён — берём первый доступный
                                ?: availableBackendEndpoints.firstOrNull()?: BackendEndpoints.LM_STUDIO_ENDPOINT
                        },
                        setter = { selectedBackend ->
                            // Всегда сохраняем настоящий id выбранного бэкенда
                            settings.state.backendEngineId = selectedBackend?.engine?.id?: BackendEngine.LM_STUDIO.id
                            settings.state.backendApiId = selectedBackend?.api?.id?: BackendApi.LM_STUDIO_API.id
                        }
                    )
                    // Опционально: обработка изменений
                    .onChangedContext { component, context ->
                        logger.info("Backend changed: ${component.selectedItem}, context: $context")
                        // Обновляем два зависимых комбобокса при смене backend
                        SwingUtilities.invokeLater {
                            val selectedEndpoint = component.selectedItem as BackendEndpoints
                            updateDependentCombos(selectedEndpoint.engine, selectedEndpoint.api)
                        }

                    }
                // Сохраняем ссылку, если нужно
            }

            row("Models:") {
                modelsCombo = comboBox(modelsModel)
                    .onChangedContext { component, context -> logger.info("$component, $context, notEditable") }
                    .bindItem(
                        getter = { settings.state.modelListEndpointUrl },
                        setter = { settings.state.modelListEndpointUrl = it.orEmpty() }
                    )
                    .component   // ← ЭТО САМОЕ ВАЖНОЕ! Здесь мы берём настоящий ComboBox
            }

            row("Chat:") {
                chatCombo = comboBox(chatModel)
                    .onChangedContext { component, context -> logger.info("$component, $context, notEditable") }
                    .bindItem(
                        getter = { settings.state.chatEndpointUrl },
                        setter = { settings.state.chatEndpointUrl = it.orEmpty() }
                    )
                    .component   // ← ЭТО САМОЕ ВАЖНОЕ! Здесь мы берём настоящий ComboBox
            }

            row("Base URL:") {
                urlCombo = comboBox(urlModel)
                    .columns(30)
                    .onChangedContext { component, context -> logger.info("$component, $context, notEditable") }
                    .bindItem(
                        getter = { settings.state.baseUrl },
                        setter = { settings.state.baseUrl = it.orEmpty() }
                    )
                    .component   // ← ЭТО САМОЕ ВАЖНОЕ! Здесь мы берём настоящий ComboBox
            }

            row("API Key:") {
                passwordField()
                    .bindText(settings.state::apiKey)
                    .columns(30)
            }

       }

        group("Model Selection") {
            row("Model:") {
                cell(modelComboBox)
                    .align(Align.FILL)
                    .bindItem(
                        getter = { settings.state.selectedModelName },
                        setter = { newName ->
                            settings.state.selectedModelName = newName.orEmpty()

                            // ← Находим модель по имени и сохраняем key
                            val selectedModel = modelsList.find { it.displayName == newName }
                            if (selectedModel != null) {
                                settings.state.selectedModelKey = selectedModel.key
                                logger.info("Model changed → Name: ${selectedModel.displayName}, Key: ${selectedModel.key}")
                            }
                        }
                    )

                button("Refresh Models") {
                    loadModelsAsync()
                }
            }

            row("System:") {
                textField().bindText(settings.state::system).align(AlignX.FILL)
            }
            row("Request settings:") {
                label("Token limit:")
                intTextField().bindIntText(settings.state::maxTokenLimit)
                checkBox("Stream").bindSelected(settings.state::stream)
            }


        }


        // Запускаем загрузку моделей сразу при создании панели
        loadModelsAsync()

        // Кастомные callbacks
        onApply {
            /* дополнительная логика при Apply */
            settings.notifyChange(project)
        }
        onReset { /* при Reset */ }
    }

    private fun loadModelsAsync() {
        isLoading = true
        modelComboBox.removeAll()
        modelComboBox.addItem("<Loading...>")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = fetchModels()
                for (model in result) {
                    logger.info("Available model: ${model.displayName}")
                }
                SwingUtilities.invokeLater {
                    modelsList.clear()
                    modelsList.addAll(result)

                    modelComboBox.removeAllItems()
                    if (result.isEmpty()) {
                        modelComboBox.addItem("No models found or error")
                    } else {
                        result.forEach { modelComboBox.addItem(it.displayName) }
                    }

                    // Восстанавливаем ранее выбранную модель по key (самый надёжный способ)
                    val savedKey = settings.state.selectedModelKey
                    val modelToSelect = modelsList.find { it.key == savedKey }
                        ?: modelsList.firstOrNull()
                    modelComboBox.selectedItem = modelToSelect?.displayName ?: ""

//                    val savedName = settings.state.selectedModelName
//                    if (savedName.isNotEmpty()) {
//                        modelComboBox.selectedItem = savedName
//                    }

                    isLoading = false
                }
            } catch (e: Exception) {
                // Если всё совсем плохо, покажем ошибку вместо вечной загрузки
                SwingUtilities.invokeLater {
                    isLoading = false
                    modelComboBox.addItem("Error: ${e.message}")
                }
            }
        }
    }

    private fun fetchModels(): List<Model> {
        val s = settings.state

        val modelListFull = s.baseUrl.trim() + s.modelListEndpointUrl.trim()
        val modelParser = ModelParser(project)
        val provider = DefaultChatConfigProvider(project)
        val url = URI.create(modelListFull).toURL()
        logger.warn("modelListFull: $modelListFull")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        return try {
            connection.inputStream.bufferedReader().use { reader ->
                val response = reader.readText()
                saveJson(project, "modelListFull", response)
                modelParser.parseModels(response, provider.buildBackend().api.id)
                //val json = Json.parseToJsonElement(response).jsonObject
                //parseModels(json)
            }
        } catch (e: Exception) {
            logger.warn("Error fetching models: ${e.message}")
            throw e
        } finally {
            connection.disconnect()
        }
    }

}