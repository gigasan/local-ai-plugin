package com.gigasan.ai.config

import com.gigasan.ai.config.storage.EndpointSettings
import com.gigasan.ai.config.storage.InstructionsService
import com.gigasan.ai.config.storage.Model
import com.gigasan.ai.config.storage.ModelCache
import com.gigasan.ai.config.storage.ModelCacheService
import com.gigasan.ai.config.storage.PluginSettingsService
import com.gigasan.ai.config.storage.ProjectSettingsService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.project.Project
import com.intellij.ui.MutableCollectionComboBoxModel
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.builder.bindItem
import com.gigasan.ai.core.JsonFileLogger
import com.gigasan.ai.core.createTooltipRenderer
import com.gigasan.ai.ui.InstructionsSettingsPanel
import com.gigasan.ai.ui.ModelSettingsPanel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.dsl.gridLayout.UnscaledGapsY
import com.intellij.util.ui.FontInfo
import com.intellij.util.ui.JBUI
import okhttp3.internal.toLongOrDefault
import javax.swing.JCheckBox

class PluginSettingsConfigurable(val project: Project) : BoundConfigurable("Local AI Settings"), JsonFileLogger {
    private val logger = Logger.getInstance("PluginSettingsConfigurable")

    private val pluginSettingsService = PluginSettingsService.instance
    private val projectSettingsService = ProjectSettingsService.getInstance(project)

    private var modelCache: ModelCache =
        ModelCacheService.instance.getSettingsFor(projectSettingsService.state.backendEndpoint)

    private val availableBackendEndpoints = BackendEndpoint.entries.filter { type ->
        type == BackendEndpoint.LM_STUDIO_ENDPOINT || pluginSettingsService.state.allowedBackendEndpoints.contains(type)
    }

    // Храним модели, чтобы обновлять их динамически
    private val modelsModel = MutableCollectionComboBoxModel<String>()
    private val chatModel = MutableCollectionComboBoxModel<String>()
    private val urlModel = MutableCollectionComboBoxModel<String>()
    val backendModel = MutableCollectionComboBoxModel(availableBackendEndpoints)

    private lateinit var urlCombo: ComboBox<String>
    private lateinit var chatCombo: ComboBox<String>
    private lateinit var modelsCombo: ComboBox<String>


    private val chatInstructionsModel = MutableCollectionComboBoxModel<String>()
    private lateinit var chatInstructionCombo: ComboBox<String>
    private val instructionsService = InstructionsService.instance

    lateinit var myPanel: DialogPanel // Основная панель
    lateinit var msp: ModelSettingsPanel // Контейнер с компонентами для ModelSettingsPanel
    lateinit var isp: InstructionsSettingsPanel // Контейнер с компонентами для InstructionsSettingsPanel

    override fun getDisplayName(): String = "Local AI Settings"

    override fun createPanel(): DialogPanel {  // ← Kotlin UI DSL Version 2
        logger.info("createPanel enter")
        lateinit var apiKeyField: Cell<JBPasswordField> // Ссылка на ячейку с полем

        val selectedEndpoint = projectSettingsService.state.backendEndpoint
        msp = ModelSettingsPanel(
            project,
            modelsComboBox = ComboBox<String>(),
            modelsList = modelCache.models.toMutableList(),
            selectedModel = null,
            isLoading = false,
            endpointSettings = pluginSettingsService.getSettingsFor(projectSettingsService.state.backendEndpoint),       // важно: передаём ссылку на существующий объект
            selectedEndpoint = selectedEndpoint,
        )
        lateinit var modelSettingsPanel: DialogPanel
        lateinit var instructionsSettingsPanel: DialogPanel


        fun refreshUIFromModel(mcc: ModelSettingsPanel) {
            modelSettingsPanel.reset()
        }
        
        // Передаем this и контейнер компонентов
        modelSettingsPanel = msp.createModelSettingsPanel(msp)

        isp = InstructionsSettingsPanel(
            project,
            instructionsService = InstructionsService.instance,
            title = "Instruction Set (Project Dependent)",
            cbProblem = null
        )

        instructionsSettingsPanel = isp.createInstructionsSettingsPanel(isp).apply {
            border = JBUI.Borders.empty(5)
        }

        // После создания backendModel и до collapsibleGroup
        updateDependentCombos(projectSettingsService.state.backendEndpoint, msp.endpointSettings, modelCache)
        updateInstruction(instructionsService)

        myPanel = panel {
            // Регистрируем внешнюю панель в жизненном цикле этой DSL панели
            onIsModified {
                modelSettingsPanel.isModified()
                instructionsSettingsPanel.isModified()
            }
            onApply {
                modelSettingsPanel.apply()
                instructionsSettingsPanel.apply()
            }
            onReset {
                modelSettingsPanel.reset()
                instructionsSettingsPanel.reset()
            }

            collapsibleGroup("Endpoint Connection Settings (Global)") {
                row("Backend:") {
                    comboBox(backendModel)
                        .bindItem(
                            getter = {
                                projectSettingsService.state.backendEndpoint
                            },
                            setter = { newBackend ->
                                if (newBackend != null) {
                                    projectSettingsService.state.backendEndpoint = newBackend
//                                    endpointSettings = pluginSettingsService.getSettingsFor(newBackend)
//                                    logger.info("SET ${newBackend} Updated $endpointSettings")
//                                    modelCache = ModelCacheService.instance.getSettingsFor(projectSettingsService.state.backendEndpoint)
//                                    logger.info("SET ${newBackend} Updated $modelCache")
                                }
                            }
                        )
                        .onChangedContext { component, context ->
                            //val selectedEndpoint = component.selectedItem as BackendEndpoint
                            msp.selectedEndpoint = component.selectedItem as BackendEndpoint
                            msp.endpointSettings = pluginSettingsService.getSettingsFor(msp.selectedEndpoint)
                            //logger.info("Backend.onChangedContext ${component.selectedItem} Updated $endpointSettings")
                            modelCache =
                                ModelCacheService.instance.getSettingsFor(projectSettingsService.state.backendEndpoint)
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
                        .component
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
                        .component
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
                        .component
                }

            }.apply {
                expanded = projectSettingsService.state.connectionExpanded
                addExpandedListener { isExpanded ->
                    projectSettingsService.state.connectionExpanded = isExpanded
                }
            }

            // Model Settings
            row { cell(modelSettingsPanel).align(AlignX.FILL) }.customize(UnscaledGapsY(top = 20, bottom = 20))

            // Instruction Set
            row { cell(instructionsSettingsPanel).align(AlignX.FILL) }.customize(UnscaledGapsY(top = 20, bottom = 20))

            collapsibleGroup("Task Compositor Settings (Project Dependent)") {
                val intellijFonts = FontInfo.getAll(false).map { it.font.family }.toTypedArray()
                // Только моноширинные шрифты (для кода)
                val monoFonts = FontInfo.getAll(true).map { it.font.family }.toTypedArray()
                row("Editor:") {
                    label("Font family:")
                    comboBox(monoFonts.toList())
                        .bindItem(
                            getter = { projectSettingsService.state.fontName },
                            setter = { value: String? -> projectSettingsService.state.fontName = value?:"" }
                            )
                    label("Size:")
                    spinner(8..72)
                        .bindIntValue(projectSettingsService.state::fontSize)
                    checkBox("Wrap lines")
                        .bindSelected(
                            getter = { projectSettingsService.state.useSoftWrap },
                            setter = { value: Boolean -> projectSettingsService.state.useSoftWrap = value }
                        )
                }
                row("Enqueue:") {
                    checkBox("Select entire lines")
                        .bindSelected(
                            getter = { projectSettingsService.state.selectEntireLines },
                            setter = { value: Boolean -> projectSettingsService.state.selectEntireLines = value }
                        )
                }
            }.apply {
                // Разворачиваем группу сразу после создания
                expanded = projectSettingsService.state.taskCompositorExpanded
                addExpandedListener { isExpanded ->
                    projectSettingsService.state.taskCompositorExpanded = isExpanded
                }
            }

            collapsibleGroup("Chat Settings (Project Dependent)") {
                row("Instruction:") {
                    chatInstructionCombo = comboBox(chatInstructionsModel)
                        .comment("General rules, role, restrictions")
                        .applyToComponent {
                            isEditable = false
                            setRenderer(createTooltipRenderer())
                        }
                        .resizableColumn().align(AlignX.FILL)
                        .bindItem(
                            getter = {
                                projectSettingsService.state.chatSystemPrompt
                            },
                            setter = { chatInstruction ->
                                projectSettingsService.state.chatSystemPrompt = chatInstruction.orEmpty()
                            }
                        )
                        .component

                }
            }.apply {
                // Разворачиваем группу сразу после создания
                expanded = projectSettingsService.state.chatExpanded
                addExpandedListener { isExpanded ->
                    projectSettingsService.state.chatExpanded = isExpanded
                }
            }


            collapsibleGroup("Advanced:") {
                row("") {
                    checkBox("Enable debug logs")
                        .bindSelected(
                            getter = { pluginSettingsService.state.enableDebugLog },
                            setter = { value: Boolean -> pluginSettingsService.state.enableDebugLog = value }
                        )
                }
            }.apply {
                // Разворачиваем группу сразу после создания
                expanded = projectSettingsService.state.advancedExpanded
                addExpandedListener { isExpanded ->
                    projectSettingsService.state.advancedExpanded = isExpanded
                }
            }

            // Запускаем загрузку моделей сразу при создании панели
            msp.loadModelsAsync(project, projectSettingsService.state.backendEndpoint, msp)

            // Кастомные callbacks
            onApply {
                logger.info("onApply")
                modelSettingsPanel.apply()
                projectSettingsService.notifyChange(project)
            }
            onReset {
                logger.info("onReset")
                modelSettingsPanel.reset()
            }

        }

        logger.info("createPanel leave")
        return myPanel
    }


    private fun updateInstruction(instructionsService: InstructionsService) {
        // chatInstruction
        val newInstructions = instructionsService.state.instructions
        chatInstructionsModel.update(newInstructions)
        val chatInstructonToSelect = newInstructions.firstOrNull { it == instructionsService.state.selectedInstruction }
        instructionsService.state.selectedInstruction = chatInstructonToSelect?:""
        if (::chatInstructionCombo.isInitialized) {
            chatInstructionCombo.selectedItem = chatInstructonToSelect
        }
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

