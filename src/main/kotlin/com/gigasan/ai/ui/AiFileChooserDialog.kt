package com.gigasan.ai.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import org.eclipse.jgit.ignore.IgnoreNode
import java.awt.BorderLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class AiFileChooserDialog(
    private val project: Project,
    private val root: VirtualFile,
    private val onFileSelected: (VirtualFile, String) -> Unit
) : DialogWrapper(project) {

    private val tree = Tree()

    private val previewArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
    }

    private var selectedFile: VirtualFile? = null

    init {
        //title = "AI File Explorer"
        title = "AiFileChooserDialog"
        init()
        setupTree()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            leftComponent = JBScrollPane(tree)
            rightComponent = JBScrollPane(previewArea)
            resizeWeight = 0.3
        }

        panel.add(split, BorderLayout.CENTER)
        panel.add(createButtonsPanel(), BorderLayout.SOUTH)

        return panel
    }


    private fun setupTree() {
        val rootNode = DefaultMutableTreeNode(root.name)
        buildTree(root, rootNode)

        tree.model = DefaultTreeModel(rootNode)

        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val file = node?.userObject as? VirtualFile ?: return@addTreeSelectionListener

            if (!file.isDirectory) {
                selectedFile = file
                previewFile(file)
            }
        }
    }

    fun isIgnoredByGitignore(
        ignore: IgnoreNode,
        root: File,
        file: File
    ): Boolean {
        val relativePath = file.relativeTo(root).path.replace("\\", "/")

        val result = ignore.isIgnored(relativePath, file.isDirectory)

        return result == IgnoreNode.MatchResult.IGNORED
    }

    private fun isIgnored(file: VirtualFile): Boolean {
        val name = file.name.lowercase()
        if (file.name.startsWith(".")) return true
        return name in setOf(
            ".git", ".idea", ".gradle", "node_modules", "build", "dist", "out"
        ) || file.path.contains("/.git/")
    }

    fun loadGitIgnore(rootPath: String): IgnoreNode {
        val ignore = IgnoreNode()

        val file = File(rootPath, ".gitignore")
        if (file.exists()) {
            file.inputStream().use {
                ignore.parse(it)
            }
        }

        return ignore
    }

    fun shouldSendToAI(file: VirtualFile): Boolean {

        val rootFile = File(root.path)
        val gitIgnore = loadGitIgnore(rootFile.path)
        if (isIgnoredByGitignore(gitIgnore, rootFile, File(file.path))) {
            return false
        }
        if (file.isDirectory) return false

        return true
    }

    private fun buildTree(file: VirtualFile, node: DefaultMutableTreeNode) {
        // 👉 ФИЛЬТР ЗДЕСЬ
        if (isIgnored(file)) return

        node.userObject = file

        file.children.forEach {
            val child = DefaultMutableTreeNode()
            node.add(child)
            if (it.isDirectory) {
                buildTree(it, child)
            } else {
                child.userObject = it
            }
        }
    }

    private fun previewFile(file: VirtualFile) {
        val content = runCatching {
            String(file.contentsToByteArray())
        }.getOrDefault("<cannot read file>")

        previewArea.text = content
    }

    private fun createButtonsPanel(): JComponent {
        val panel = JPanel()

        val openBtn = JButton("Open").apply {
            addActionListener {
                selectedFile?.let {
                    onFileSelected(it, "open")
                    close(OK_EXIT_CODE)
                }
            }
        }

        val explainBtn = JButton("Explain").apply {
            addActionListener {
                selectedFile?.let {
                    onFileSelected(it, "explain")
                }
            }
        }

        val refactorBtn = JButton("Refactor").apply {
            addActionListener {
                selectedFile?.let {
                    onFileSelected(it, "refactor")
                }
            }
        }

        val testsBtn = JButton("Generate tests").apply {
            addActionListener {
                selectedFile?.let {
                    onFileSelected(it, "tests")
                }
            }
        }

        panel.add(openBtn)
        panel.add(explainBtn)
        panel.add(refactorBtn)
        panel.add(testsBtn)

        return panel
    }




}