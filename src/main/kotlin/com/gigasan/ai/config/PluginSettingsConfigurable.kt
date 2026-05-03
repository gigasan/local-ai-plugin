package com.gigasan.ai.config

import com.gigasan.ai.config.storage.EndpointSettings
import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.config.storage.ModelCache
import com.gigasan.ai.config.storage.ModelCacheService
import com.gigasan.ai.config.storage.PluginSettings
import com.gigasan.ai.config.storage.ProjectSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.project.Project
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.builder.bindItem
import com.gigasan.ai.core.JsonFileLogger
import com.gigasan.ai.ui.ModelSettingsPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.intellij.util.ui.FontInfo
import okhttp3.internal.toLongOrDefault

class PluginSettingsConfigurable(val project: Project) : BoundConfigurable("Local AI Settings"), JsonFileLogger {
    private val logger = Logger.getInstance("PluginSettingsConfigurable")

    private val pluginSettings = PluginSettings.instance
    private val projectSettings = ProjectSettings.getInstance(project)

    //var endpointSettings: EndpointSettings
    private var modelCache: ModelCache =
        ModelCacheService.instance.getSettingsFor(projectSettings.state.backendEndpoint)

    private val availableBackendEndpoints = BackendEndpoint.entries.filter { type ->
        type == BackendEndpoint.LM_STUDIO_ENDPOINT || pluginSettings.state.allowedBackendEndpoints.contains(type)
    }

    // Храним модели, чтобы обновлять их динамически
    private val modelsModel = MutableCollectionComboBoxModel<String>()
    private val chatModel = MutableCollectionComboBoxModel<String>()
    private val urlModel = MutableCollectionComboBoxModel<String>()
    val backendModel = MutableCollectionComboBoxModel(availableBackendEndpoints)

    private lateinit var urlCombo: ComboBox<String>
    private lateinit var chatCombo: ComboBox<String>
    private lateinit var modelsCombo: ComboBox<String>

    lateinit var myPanel: DialogPanel // Основная панель
    lateinit var msp: ModelSettingsPanel // Контейнер с компонентами для ModelSettingsPanel

    //lateinit var modelCombo: ComboBox<String>

    override fun getDisplayName(): String = "Local AI Settings"

    override fun createPanel(): DialogPanel {  // ← Kotlin UI DSL Version 2
        lateinit var apiKeyField: Cell<JBPasswordField> // Ссылка на ячейку с полем

        msp = ModelSettingsPanel(
            project,
            modelsComboBox = ComboBox<String>(),
            modelsList = mutableListOf<Model>(),
            isLoading = false,
            endpointSettings = pluginSettings.getSettingsFor(projectSettings.state.backendEndpoint),       // важно: передаём ссылку на существующий объект
            selectedEndpoint = projectSettings.state.backendEndpoint,
        )
        lateinit var modelSettingsPanel: DialogPanel
        fun refreshUIFromModel(mcc: ModelSettingsPanel) {
            modelSettingsPanel.reset()
        }
        
        // Передаем this и контейнер компонентов
        modelSettingsPanel = msp.createModelSettingsPanel(msp)


        logger.info("createPanel enter")

        // После создания backendModel и до collapsibleGroup
        updateDependentCombos(projectSettings.state.backendEndpoint, msp.endpointSettings, modelCache)

        myPanel = panel {
            // Регистрируем вашу внешнюю панель в жизненном цикле этой DSL панели
            onIsModified { modelSettingsPanel.isModified() }
            onApply { modelSettingsPanel.apply() }
            onReset { modelSettingsPanel.reset() }

            collapsibleGroup("Endpoint Connection Settings (Global)") {
                row("Backend:") {
                    comboBox(backendModel)
                        .bindItem(
                            getter = {
                                projectSettings.state.backendEndpoint
                            },
                            setter = { newBackend ->
                                if (newBackend != null) {
                                    projectSettings.state.backendEndpoint = newBackend
//                                    endpointSettings = pluginSettings.getSettingsFor(newBackend)
//                                    logger.info("SET ${newBackend} Updated $endpointSettings")
//                                    modelCache = ModelCacheService.instance.getSettingsFor(projectSettings.state.backendEndpoint)
//                                    logger.info("SET ${newBackend} Updated $modelCache")
                                }
                            }
                        )
                        .onChangedContext { component, context ->
                            //val selectedEndpoint = component.selectedItem as BackendEndpoint
                            msp.selectedEndpoint = component.selectedItem as BackendEndpoint
                            msp.endpointSettings = pluginSettings.getSettingsFor(msp.selectedEndpoint)
                            //logger.info("Backend.onChangedContext ${component.selectedItem} Updated $endpointSettings")
                            modelCache =
                                ModelCacheService.instance.getSettingsFor(projectSettings.state.backendEndpoint)
                            //logger.info("Backend.onChangedContext ${component.selectedItem} Updated $modelCache")
                            updateDependentCombos(msp.selectedEndpoint, msp.endpointSettings, modelCache)
                            refreshUIFromModel(msp)
                            apiKeyField.component.text = msp.endpointSettings.apiKey
                            msp.loadModelsAsync(project, msp.selectedEndpoint, msp)
                        }
                }
                row("API Key:") {
                    apiKeyField = passwordField().bindText(
                        {
                            //logger.info("GET uiSettings.apiKey=${endpointSettings.apiKey.length}")
                            msp.endpointSettings.apiKey
                        },
                        { apiKey ->
                            msp.endpointSettings.apiKey = apiKey
                            //logger.info("SET uiSettings.apiKey=${apiKey.length}")
                        }
                    )
                        .resizableColumn()
                        .align(AlignX.FILL)
                }
                row("Base URL:") {
                    urlCombo = comboBox(urlModel)
                        .columns(30)
                        //.onChangedContext { component, context -> logger.info("urlCombo.onChangedContext $component, notEditable") }
                        .bindItem(
                            getter = { msp.endpointSettings.baseUrl },
                            setter = { msp.endpointSettings.baseUrl = it.orEmpty() }
                        )
                        .component   // ← ЭТО САМОЕ ВАЖНОЕ! Здесь мы берём настоящий ComboBox
                }

                row("Models:") {
                    modelsCombo = comboBox(modelsModel)
                        //.onChangedContext { component, context -> logger.info("modelsCombo.onChangedContext $component, notEditable") }
                        .bindItem(
                            getter = {
                                //logger.info("GET uiSettings.modelListEndpointUrl=${endpointSettings.modelListEndpointUrl}")
                                msp.endpointSettings.modelListEndpointUrl
                            },
                            setter = { modelUrl ->
                                msp.endpointSettings.modelListEndpointUrl = modelUrl.orEmpty()
                                //logger.info("SET uiSettings.modelListEndpointUrl=$modelUrl")
                            }
                        )
                        .component   // ← ЭТО САМОЕ ВАЖНОЕ! Здесь мы берём настоящий ComboBox
                    label("Cache TTL, seconds:")
                    textField()
                        .bindText(
                            getter = { ModelCacheService.instance.state.ttlSec.toString() },
                            setter = { text: String -> ModelCacheService.instance.state.ttlSec = text.toLongOrDefault(5) }
                        )
                }

                row("Chat:") {
                    chatCombo = comboBox(chatModel)
                        //.onChangedContext { component, context -> logger.info("chatCombo.onChangedContext $component, notEditable") }
                        .bindItem(
                            getter = {
                                //logger.info("GET uiSettings.chatEndpointUrl=${endpointSettings.chatEndpointUrl}")
                                msp.endpointSettings.chatEndpointUrl
                            },
                            setter = { chatUrl ->
                                msp.endpointSettings.chatEndpointUrl = chatUrl.orEmpty()
                            }
                        )
                        .component   // ← ЭТО САМОЕ ВАЖНОЕ! Здесь мы берём настоящий ComboBox
                }


//                row("Cache TTL:") {
//                    label("Model list cache TTL (Time To Live), seconds:")
//                    textField()
//                        .bindText(
//                            getter = { ModelCacheService.instance.state.ttlSec.toString() },
//                            setter = { text: String -> ModelCacheService.instance.state.ttlSec = text.toLongOrDefault(5) }
//                        )
//                }
            }.apply {
                // Разворачиваем группу сразу после создания
                expanded = projectSettings.state.connectionExpanded
                addExpandedListener { isExpanded ->
                    projectSettings.state.connectionExpanded = isExpanded
                }
            }

            // Endpoint Settings
            row { cell(modelSettingsPanel).align(AlignX.FILL) }.customize(UnscaledGapsY(top = 20, bottom = 20))

            collapsibleGroup("Chat Settings (Project Dependent)") {
                row("System prompt:") {
                    textField()
                        .resizableColumn().align(AlignX.FILL)
                        .bindText(
                            getter = { projectSettings.state.chatSystemPrompt },
                            setter = { text -> projectSettings.state.chatSystemPrompt = text }
                        )
                }
            }.apply {
                // Разворачиваем группу сразу после создания
                expanded = projectSettings.state.chatExpanded
                addExpandedListener { isExpanded ->
                    projectSettings.state.chatExpanded = isExpanded
                }
            }

            collapsibleGroup("") {
                val intellijFonts = FontInfo.getAll(false).map { it.font.family }.toTypedArray()
                // Только моноширинные шрифты (для кода)
                val monoFonts = FontInfo.getAll(true).map { it.font.family }.toTypedArray()
                row("Editor:") {
                    label("Font family:")
                    comboBox(monoFonts.toList())
                        .bindItem(
                            getter = { projectSettings.state.fontName },
                            setter = { value: String? -> projectSettings.state.fontName = value?:"" }
                            )
                    label("Size:")
                    spinner(8..72)
                        .bindIntValue(projectSettings.state::fontSize)
                    checkBox("Wrap lines")
                        .bindSelected(
                            getter = { projectSettings.state.useSoftWrap },
                            setter = { value: Boolean -> projectSettings.state.useSoftWrap = value }
                        )
                }
                row("Enqueue:") {
                    checkBox("Select entire lines")
                        .bindSelected(
                            getter = { projectSettings.state.selectEntireLines },
                            setter = { value: Boolean -> projectSettings.state.selectEntireLines = value }
                        )
                }
            }.apply {
                // Разворачиваем группу сразу после создания
                expanded = projectSettings.state.promptsExpanded
                addExpandedListener { isExpanded ->
                    projectSettings.state.promptsExpanded = isExpanded
                }
            }



            // Запускаем загрузку моделей сразу при создании панели
            msp.loadModelsAsync(project, projectSettings.state.backendEndpoint, msp)

            // Кастомные callbacks
            onApply {
                logger.info("onApply")
                modelSettingsPanel.apply()
                projectSettings.notifyChange(project)
            }
            onReset {
                logger.info("onReset")
                modelSettingsPanel.reset()
            }

        }

        logger.info("createPanel leave")
        return myPanel
    }


    private fun updateDependentCombos(endpoint: BackendEndpoint, endpointSettings: EndpointSettings, modelCache: ModelCache) {

        // URL
        val newUrl = endpoint.engine.defaultHost
        urlModel.update(newUrl)
        val urlToSelect = newUrl.firstOrNull { it == endpointSettings.baseUrl }?: newUrl.firstOrNull() ?: ""
        logger.info("updateDependentCombos url=${urlToSelect}")
        endpointSettings.baseUrl = urlToSelect
        if (::urlCombo.isInitialized) {
            urlCombo.selectedItem = urlToSelect
        }

        // Models
        val newModels = endpoint.defaultModelList
        modelsModel.update(newModels)
        val modelToSelect = newModels.firstOrNull { it == endpointSettings.modelListEndpointUrl }?: newModels.firstOrNull() ?: ""
        logger.info("updateDependentCombos models=${modelToSelect}")
        endpointSettings.modelListEndpointUrl = modelToSelect
        if (::modelsCombo.isInitialized) {
            modelsCombo.selectedItem = modelToSelect
        }

        // Chat
        val newChat = endpoint.defaultResponses
        chatModel.update(newChat)
        val chatToSelect = newChat.firstOrNull { it == endpointSettings.chatEndpointUrl }?: newChat.firstOrNull() ?: ""
        logger.info("updateDependentCombos chat=${chatToSelect}")
        endpointSettings.chatEndpointUrl = chatToSelect
        if (::chatCombo.isInitialized) {
            chatCombo.selectedItem = chatToSelect
        }

        // Model cache -> modelCombo (modelName <-> modelKey)
        modelCache.models.forEach { model ->
            //logger.info("updateDependentCombos model ${model.displayName} is in list ")
        }

        logger.info("updateDependentCombos modelCache=${modelCache.models.joinToString(", ") { it.displayName }}")

        val newModelName = endpointSettings.selectedModelName
        val newModelKey = endpointSettings.selectedModelKey
        logger.info("updateDependentCombos modelName=$newModelName ModelKey=$newModelKey")
//        if (::modelCombo.isInitialized) {
//            modelCombo.selectedItem = newModelName
//        }
    }

}

