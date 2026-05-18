package com.gigasan.ai.config.storage

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil

// ========== Настройки промптов ==========
@State(name = "com.gigasan.ai.ui.InstructionsService", storages = [Storage("InstructionsService.xml")])
@Service(Service.Level.APP)
class InstructionsService : PersistentStateComponent<InstructionsService.State> {
    data class State(
        var instructions: MutableList<String> = mutableListOf(),
        var problems: MutableList<String> = mutableListOf(),
        var selectedInstruction: String = "",
        var selectedProblem: String = "",
        var enabledProblem: Boolean = true,
    )

    private var myState = State()

    private val logger = Logger.getInstance(InstructionsService::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()


    // Блок init выполняется ВСЕГДА при создании сервиса
    init {
        // Загружаем дефолты сразу. Если потом вызовется loadState,
        // он просто перезапишет эти списки актуальными данными из XML.
        loadDefaultInstructions()
        loadDefaultProblems()
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        // Если после загрузки списки пусты (первый запуск), наполняем их
        if (myState.instructions.isEmpty() && myState.problems.isEmpty()) {
            loadDefaultInstructions()
            loadDefaultProblems()
        }
    }

    fun loadDefaultInstructions(onSave: (() -> Unit)? = null) {
        try {
            // Читаем файл прямо из JAR-файла плагина
            val stream = javaClass.getResourceAsStream("/defaults/instructions.json")
            val jsonString = stream?.bufferedReader()?.use { it.readText() }
            val type = object : TypeToken<List<String>>() {}.type
            val instructions: MutableList<String> = gson.fromJson(jsonString, type)

            myState.instructions.clear()
            instructions.forEach { myState.instructions.add(it) }

            if (myState.instructions.isNotEmpty()) {
                myState.selectedInstruction = myState.instructions[0]
            }
            onSave?.invoke()
            logger.info("Imported ${myState.instructions.size} instructions")
        } catch (e: Exception) {
            logger.warn(e)
        }
    }

    fun loadDefaultProblems(onSave: (() -> Unit)? = null) {
        try {
            // Читаем файл прямо из JAR-файла плагина
            val stream = javaClass.getResourceAsStream("/defaults/problems.json")
            val jsonString = stream?.bufferedReader()?.use { it.readText() }
            val type = object : TypeToken<List<String>>() {}.type
            val problems: MutableList<String> = gson.fromJson(jsonString, type)

            myState.problems.clear()
            problems.forEach { myState.problems.add(it) }

            if (myState.problems.isNotEmpty()) {
                myState.selectedProblem = myState.problems[0]
            }
            onSave?.invoke()
            logger.info("Imported ${myState.problems.size} problems")
        } catch (e: Exception) {
            logger.warn(e)
        }
    }

    companion object {
        val instance: InstructionsService get() = service()
    }
}