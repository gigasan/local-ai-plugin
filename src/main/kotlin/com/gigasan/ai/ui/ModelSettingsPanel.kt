package com.gigasan.ai.ui

import com.gigasan.ai.config.BackendEndpoint
import com.gigasan.ai.config.DefaultChatConfigProvider
import com.gigasan.ai.config.ModelParser
import com.gigasan.ai.config.storage.EndpointSettings
import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.config.storage.ModelCache
import com.gigasan.ai.config.storage.ModelCacheService
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.config.storage.ProjectSettingsService
import com.gigasan.ai.config.storage.supportsReasoning
import com.gigasan.ai.core.FileLogger
import com.gigasan.ai.core.createModelListRenderer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.jetbrains.rd.util.URI
import java.net.HttpURLConnection
import javax.swing.JLabel
import javax.swing.SwingUtilities

data class ModelSettingsPanel(
    // Ссылки на внешние объекты (чтобы можно было обновлять UI из асинхронного кода)
    val project: Project,
    // Основные изменяемые компоненты
    val modelsComboBox: ComboBox<String>,
    val modelsList: MutableList<Model> = mutableListOf(),
    var selectedModel: Model? = null,
    // Эти поля должны быть изменяемыми снаружи и внутри
    var isLoading: Boolean = false,
    var endpointSettings: EndpointSettings,
    var selectedEndpoint: BackendEndpoint,
) {
    val logger = Logger.getInstance("ModelSettingsPanel")

    lateinit var labelReasoning: JLabel
    lateinit var reasoningComboBox: ComboBox<String>

    lateinit var keepAliveLabel: JLabel
    lateinit var keepAliveTextField: JBTextField

    fun createModelSettingsPanel(components: ModelSettingsPanel): DialogPanel {
        val project = components.project
        val projectSettings = ProjectSettingsService.getInstance(project).state
        var panelExpanded = projectSettings.modelSelectionExpanded

        selectedModel = components.modelsList.find { it.displayName == components.endpointSettings.selectedModelName }
        return panel {
            collapsibleGroup("Model Settings (Endpoint Dependent)") {
                row("Model:") {
                    cell(components.modelsComboBox) // Используем конкретный экземпляр
                        .resizableColumn()
                        .align(AlignX.FILL)
                        .applyToComponent {

                            renderer = createModelListRenderer(components.modelsList)
                            addActionListener {
                                val selectedName = selectedItem as? String
                                val model = components.modelsList.find { it.displayName == selectedName }
                                components.selectedModel = model
                                updateReasoningUI(model, components, labelReasoning, reasoningComboBox, panelExpanded)
                                updateKeepAliveUI(model, components, keepAliveLabel, keepAliveTextField, panelExpanded)
                            }
                        }
                        .bindItem(
                            getter = { components.endpointSettings.selectedModelName },
                            setter = { newName ->
                                components.endpointSettings.selectedModelName = newName.orEmpty()
                                val found = components.modelsList.find { it.displayName == newName }
                                components.selectedModel = found
                                found?.let { components.endpointSettings.selectedModelKey = it.key }
                            }
                        )
                    button("Refresh Models") {
                        loadModelsAsync(project, components.selectedEndpoint, components)
                    }.align(AlignX.RIGHT)
                }

                row("System:") {
                    textField()
                        .resizableColumn().align(AlignX.FILL)
                        .bindText(
                            getter = { components.endpointSettings.system },
                            setter = { text -> components.endpointSettings.system = text }
                        )
                }.visible(false)
                row() {
                    label("Max context:")
                    textField()
                        .bindText(
                            getter = { components.endpointSettings.maxContext.toString() },
                            setter = { value: String ->
                                components.endpointSettings.maxContext = value.toLongOrNull() ?: 0L
                            }
                        )
                        .columns(10)
                    label("Token limit:")
                    textField()
                        .bindText(
                            getter = { components.endpointSettings.maxTokenLimit.toString() },
                            setter = { value: String ->
                                components.endpointSettings.maxTokenLimit = value.toLongOrNull() ?: 0L
                            }
                        )
                        .columns(10)

                    keepAliveLabel = label("Keep alive, min:")
                        .visible(false)
                        .component
                    keepAliveTextField = intTextField()
                        .bindIntText(
                            getter = { components.endpointSettings.keep_alive },
                            setter = { value: Int -> components.endpointSettings.keep_alive = value }
                        )
                        .visible(false)
                        .component

                    labelReasoning = label("Reasoning:")
                        .visible(false)
                        .component
                    reasoningComboBox = comboBox(emptyList<String>())
                        .bindItem(
                            // БЕЗОПАСНАЯ ПРИВЯЗКА без toNullableProperty
                            getter = { components.endpointSettings.reasoning },
                            setter = { components.endpointSettings.reasoning = it ?: "" } // Защита от null
                        )
                        .visible(false)
                        .component
                }

                row() {
                    label("Temperature:")
                    slider(0, 10, 1, 0) // от 0 до 10, шаг 1
                        .bindValue(
                            getter = {
                                // Превращаем "0.7" в 7 для слайдера
                                val currentTemp = components.endpointSettings.temperature
                                (currentTemp * 10).toInt().coerceIn(0, 10)
                            },
                            setter = { newValue ->
                                // Превращаем 7 от слайдера в "0.7" для модели
                                components.endpointSettings.temperature = (newValue / 10.0f)
                            }
                        )

                    checkBox("Stream")
                        .bindSelected(
                            getter = { components.endpointSettings.stream },
                            setter = { value: Boolean -> components.endpointSettings.stream = value }
                        )
//                    val cb = checkBox("Logprobs")
//                        .bindSelected(
//                            getter = { components.endpointSettings.logprobs },
//                            setter = { value: Boolean -> components.endpointSettings.logprobs = value }
//                        )
//                    label("Top logprobs:")
//                        .visibleIf(cb.selected)
//                    intTextField()
//                        .bindIntText(
//                            getter = { components.endpointSettings.top_logprobs },
//                            setter = { value: Int -> components.endpointSettings.top_logprobs = value }
//                        )
//                        .visibleIf(cb.selected)
                }.layout(RowLayout.LABEL_ALIGNED)

                // Сразу после создания панели инициализируем состояние для текущей модели
                val initialModel = components.modelsList.find { it.displayName == components.modelsComboBox.selectedItem }
                updateKeepAliveUI(initialModel, components, keepAliveLabel, keepAliveTextField, panelExpanded)
                updateReasoningUI(initialModel, components, labelReasoning, reasoningComboBox, panelExpanded)
            }.apply {
                expanded = projectSettings.modelSelectionExpanded
                addExpandedListener { isExpanded ->
                    projectSettings.modelSelectionExpanded = isExpanded
                    panelExpanded = isExpanded
                }
            }.customize(UnscaledGapsY(bottom = 0))

        }
    }

    private fun updateReasoningUI(
        model: Model?,
        components: ModelSettingsPanel,
        lbl: JLabel? = null,
        cb: ComboBox<String>? = null,
        isParentExpanded: Boolean = false
    ) {
        // В идеале ссылки на row и cb нужно сохранить в компонентах или найти в панели
        if (model == null)
        {
            cb?.isVisible = false
            lbl?.isVisible = false
            return
        }

        val supports = model.supportsReasoning

        // 1. Управляем видимостью всей строки
        if (supports && cb != null) {
            // 2. Обновляем список доступных опций в комбобоксе
            cb.removeAllItems()
            model.reasoningOptions.forEach { cb.addItem(it) }

            // 3. Устанавливаем дефолтное значение, если в настройках еще пусто
            if (components.endpointSettings.reasoning.isEmpty()) {
                cb.selectedItem = model.defaultReasoning ?: model.reasoningOptions.firstOrNull()
            } else {
                cb.selectedItem = components.endpointSettings.reasoning
            }

            // 4. Блокируем, если выбора нет
            cb.isEnabled = model.reasoningOptions.size > 1

            cb.isVisible = isParentExpanded
            lbl?.isVisible = isParentExpanded
        } else {
            cb?.isVisible = false
            lbl?.isVisible = false
        }
    }

    private fun updateKeepAliveUI(
        model: Model?,
        components: ModelSettingsPanel,
        label: JLabel,
        textField: JBTextField,
        isParentExpanded: Boolean = false
    ) {
        if (isParentExpanded && model != null && components.selectedEndpoint == BackendEndpoint.OLLAMA_ENDPOINT) {
            label.isVisible = true
            textField.isVisible = true
        } else {
            label.isVisible = false
            textField.isVisible = false
        }
    }

    fun loadModelsAsync(
        project: Project,
        targetEndpoint: BackendEndpoint,
        components: ModelSettingsPanel
    ) {
        if (components.isLoading) return
        components.isLoading = true

        val url = targetEndpoint.engine.defaultHost.first() + targetEndpoint.defaultModelList.first()
        val apiId = targetEndpoint.api.id
        val global = PluginSettingsService.instance
        val currentEndpoint = ProjectSettingsService.getInstance(project).state.backendEndpoint
        val apiKey = global.getSettingsFor(targetEndpoint).apiKey
        val endpointSettings = global.getSettingsFor(targetEndpoint)
        val cacheService = ModelCacheService.instance
        val modelCache = cacheService.getSettingsFor(targetEndpoint)

        if (currentEndpoint != targetEndpoint) {
            logger.warn("loadModelsAsync UI is different: currentEndpoint=${currentEndpoint.name} != targetEndpoint=${targetEndpoint.name}")
        }
        logger.info("loadModelsAsync targetEndpoint=${targetEndpoint.name} use corresponding apiKey, endpointSettings and modelCache")
        logger.info("loadModelsAsync modelCache=${modelCache.models.size} apiKey=${apiKey.length}")

        // 🟢 1. показать кеш сразу
        if (modelCache.models.isNotEmpty()) {
            //logger.info("loadModelsAsync applyModelsToUI")
            applyModelsToUI(endpointSettings, modelCache.models, components)
        } else {
            //logger.info("loadModelsAsync showLoading")
            showLoading(components)
        }
        // 🟡 2. если кеш валиден — можно не дергать сеть
        if (modelCache.models.isNotEmpty() && cacheService.isValid(modelCache)) {
            //logger.info("loadModelsAsync cache is valid")
            logger.info("loadModelsAsync → cache is valid, skipping network request")
            //applyModelsToUI(endpointSettings, modelCache.models, components)
            components.isLoading = false
            return
        }

        logger.info("loadModelsAsync → cache invalid or empty, fetching from network...")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val freshModels = fetchModels(project, targetEndpoint, url, apiKey, apiId)
                //logger.info("loadModelsAsync result=${freshModels.joinToString(", ") { it.displayName }}")
                logger.info("loadModelsAsync fetched ${freshModels.size} models")
                // 💾 сохранить кеш
                cacheService.save(
                    endpoint = targetEndpoint,
                    ModelCache(
                        models = freshModels,
                        timestamp = System.currentTimeMillis()
                    )
                )

                SwingUtilities.invokeLater {
                    applyModelsToUI(endpointSettings, freshModels, components)
                    // ВАЖНО: Обновляем UI только если юзер всё еще на этом эндпоинте
                    if (currentEndpoint == targetEndpoint) {
                        // если endpoints отличаются значит мы переключились на другой endpoint
                        // без нажатия Apply - это нормальное состояние
                    } else {
                        //logger.warn("loadModelsAsync UI is different: currentEndpoint=$currentEndpoint != targetEndpoint=$targetEndpoint")
                    }
                    components.isLoading = false
                }

            } catch (e: Exception) {
                logger.warn("Failed to load models for $targetEndpoint", e)
                SwingUtilities.invokeLater {
                    components.isLoading = false
                    showError(e, components)
                }
            }
        }
    }

    fun fetchModels(
        project: Project,
        targetEndpoint: BackendEndpoint,
        endpoint: String,
        key: String,
        apiId: Int
    ): List<Model> {
        val modelParser = ModelParser(project)
        val provider = DefaultChatConfigProvider(project)
        val url = URI.create(endpoint).toURL()
        //logger.warn("endpoint: $endpoint")
        val connection = url.openConnection() as HttpURLConnection

        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.setRequestProperty("Content-Type", "application/json")

        return try {
            connection.inputStream.bufferedReader().use { reader ->
                val response = reader.readText()
                FileLogger.saveJson(project, "modelList_${targetEndpoint.name}", response)
                modelParser.parseModels(response, apiId)
            }
        } catch (e: Exception) {
            logger.warn("Error fetching models: ${e.message}")
            throw e
        } finally {
            connection.disconnect()
        }
    }

    fun showLoading(components: ModelSettingsPanel) {
        components.modelsComboBox.removeAllItems()
        components.modelsComboBox.addItem("<Loading...>")
    }

    fun showError(e: Exception, components: ModelSettingsPanel) {
        components.modelsComboBox.removeAllItems()
        components.modelsComboBox.addItem("Error: ${e.message}")
    }

    fun applyModelsToUI(uiSettings: EndpointSettings, models: List<Model>, components: ModelSettingsPanel) {
        components.modelsList.clear()
        components.modelsList.addAll(models)

        components.modelsComboBox.removeAllItems()

        if (models.isEmpty()) {
            components.modelsComboBox.addItem("No models found")
            return
        }

        models.forEach { components.modelsComboBox.addItem(it.displayName) }

        val savedKey = uiSettings.selectedModelKey
        val selected = models.find { it.key == savedKey }
            ?: models.first()

        components.selectedModel = selected
        components.modelsComboBox.selectedItem = selected.displayName
    }
}