package com.gigasan.localai

import javax.swing.*
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

class ChatWindow {
    private val frame = JFrame("Простой чат")
    private val chatArea = JTextArea()
    private val inputField = JTextField()
    private val sendButton = JButton("Отправить")

    init {
        chatArea.isEditable = false
        val scrollPane = JScrollPane(chatArea)
        val panel = JPanel(BorderLayout())
        panel.add(inputField, BorderLayout.CENTER)
        panel.add(sendButton, BorderLayout.EAST)

        frame.layout = BorderLayout()
        frame.add(scrollPane, BorderLayout.CENTER)
        frame.add(panel, BorderLayout.SOUTH)
        frame.setSize(400, 600)

        sendButton.addActionListener { sendMessage() }
        inputField.addActionListener { sendMessage() }
    }

    private fun sendMessage() {
        val message = inputField.text
        if (message.isNotBlank()) {
            chatArea.append("Ты: $message\n")
            inputField.text = ""

            // имитация ответа бота
            Thread {
                Thread.sleep(500)
                SwingUtilities.invokeLater {
                    chatArea.append("Бот: Привет! Ты написал: $message\n")
                }
            }.start()
        }
    }

    fun show() {
        frame.isVisible = true
    }
}