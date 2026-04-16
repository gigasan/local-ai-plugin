package com.gigasan.ai.core

import com.intellij.lang.Language
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

data class Languages(val project: Project) {

    // HTML + JS / SQL в строках + работа с LLM
    fun detectLanguageFromEditorAdvanced(project: Project, editor: Editor): String {
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
            ?: return "clike"

        val offset = editor.caretModel.offset

        val injectedManager = InjectedLanguageManager.getInstance(project)
        val injectedPsi = injectedManager.findInjectedElementAt(psiFile, offset)

        val element = injectedPsi ?: psiFile.findElementAt(offset)

        val language = element?.language ?: psiFile.language

        return mapLanguage(language)
    }

    // простой детект
    fun detectLanguageFromEditor(project: Project, editor: Editor): String {
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
            ?: return "clike"

        val selectionModel = editor.selectionModel

        val element = if (selectionModel.hasSelection()) {
            val start = selectionModel.selectionStart
            val end = selectionModel.selectionEnd

            PsiTreeUtil.findCommonParent(
                psiFile.findElementAt(start),
                psiFile.findElementAt(end - 1)
            )
        } else {
            val offset = editor.caretModel.offset
            psiFile.findElementAt(offset)
        }

        val language = element?.language ?: psiFile.language

        return mapLanguage(language) // из предыдущего ответа
    }

    // --- основной метод ---
    fun detectLanguageSmart(project: Project, file: VirtualFile): String {
        val psiFile = PsiManager.getInstance(project).findFile(file)

        psiFile?.let {
            val mapped = mapLanguage(it.language)
            if (mapped != "clike") return mapped
        }

        return detectByExtension(file.name) ?: "clike"
    }

    fun mapLanguage(language: Language): String {
        val id = language.id.lowercase()

        return when {
            // JVM
            id.contains("kotlin") -> "kotlin"
            id == "java" -> "java"
            id.contains("groovy") -> "groovy"
            id.contains("scala") -> "scala"

            // scripting
            id.contains("python") -> "python"
            id.contains("ruby") -> "ruby"
            id.contains("perl") -> "perl"
            id.contains("php") -> "php"

            // web
            id.contains("javascript") -> "javascript"
            id.contains("typescript") -> "typescript"
            id.contains("json") -> "json"
            id.contains("html") -> "html"
            id.contains("xml") -> "xml"
            id.contains("css") -> "css"
            id.contains("scss") -> "scss"
            id.contains("sass") -> "sass"

            // systems
            id == "c" -> "c"
            id.contains("c++") || id.contains("cpp") -> "cpp"
            id.contains("objectivec") -> "c"
            id.contains("rust") -> "rust"
            id.contains("go") -> "go"
            id.contains("zig") -> "zig"

            // functional
            id.contains("haskell") -> "haskell"
            id.contains("ocaml") -> "ocaml"
            id.contains("f#") -> "fsharp"
            id.contains("clojure") -> "clojure"
            id.contains("lisp") -> "lisp"
            id.contains("scheme") -> "scheme"

            // shell
            id.contains("bash") || id.contains("shell") -> "bash"
            id.contains("zsh") -> "bash"
            id.contains("powershell") -> "powershell"

            // data / config
            id.contains("yaml") -> "yaml"
            id.contains("toml") -> "toml"
            id.contains("ini") -> "ini"
            id.contains("properties") -> "ini"

            // db
            id.contains("sql") -> "sql"

            // other
            id.contains("dart") -> "dart"
            id.contains("swift") -> "swift"
            id.contains("objective-c") -> "c"
            id.contains("kotlin script") -> "kotlin"
            id.contains("makefile") -> "makefile"
            id.contains("docker") -> "dockerfile"
            id.contains("r") -> "r"
            id.contains("matlab") -> "matlab"
            id.contains("fortran") -> "fortran"
            id.contains("pascal") -> "pascal"
            id.contains("nim") -> "nim"
            id.contains("crystal") -> "crystal"
            id.contains("elixir") -> "elixir"
            id.contains("erlang") -> "erlang"

            else -> "clike"
        }
    }

    fun detectByExtension(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()

        return when (ext) {
            // JVM
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "groovy" -> "groovy"
            "scala" -> "scala"

            // scripting
            "py" -> "python"
            "rb" -> "ruby"
            "pl" -> "perl"
            "php" -> "php"

            // web
            "js" -> "javascript"
            "ts" -> "typescript"
            "json" -> "json"
            "html", "htm" -> "html"
            "xml" -> "xml"
            "css" -> "css"
            "scss" -> "scss"
            "sass" -> "sass"

            // systems
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp", "hh" -> "cpp"
            "rs" -> "rust"
            "go" -> "go"
            "zig" -> "zig"

            // functional
            "hs" -> "haskell"
            "ml" -> "ocaml"
            "fs" -> "fsharp"
            "clj" -> "clojure"
            "lisp", "lsp" -> "lisp"
            "scm" -> "scheme"

            // shell
            "sh" -> "bash"
            "zsh" -> "bash"
            "ps1" -> "powershell"

            // config
            "yml", "yaml" -> "yaml"
            "toml" -> "toml"
            "ini", "cfg" -> "ini"
            "properties" -> "ini"

            // db
            "sql" -> "sql"

            // other
            "dart" -> "dart"
            "swift" -> "swift"
            "r" -> "r"
            "m" -> "matlab"
            "f90", "f95" -> "fortran"
            "pas" -> "pascal"
            "nim" -> "nim"
            "cr" -> "crystal"
            "ex", "exs" -> "elixir"
            "erl" -> "erlang"
            "mk" -> "makefile"
            "dockerfile" -> "dockerfile"

            else -> null
        }
    }

}