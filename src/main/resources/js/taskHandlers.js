// taskHandlers.js — чистый JS, без всяких inject
/**
 * 1. Обработка событий для элементов <summary>
 */
(function() {
    const boundSummaries = new WeakSet();

    function attachTaskHandlers() {
        document.querySelectorAll("summary").forEach(el => {
            if (boundSummaries.has(el)) return;
            boundSummaries.add(el);

            const taskId = el.getAttribute('data-task-id');
            if (!taskId) return;

            el.addEventListener("click", (e) => {
                // Если кликнули именно на кнопку удаления, игнорируем обычный клик по плашке
                if (e.target.classList.contains('delete-task-btn')) {
                    return;
                }
                window.taskBridge?.send("click:" + taskId);
            });

            el.addEventListener("mouseenter", () => {
                window.taskBridge?.send("hover:" + taskId);
            });

            el.addEventListener("mouseleave", () => {
                window.taskBridge?.send("hover:exit");
            });

            // Находим кнопку удаления ИМЕННО внутри этого конкретного summary
            const deleteBtn = el.querySelector('.delete-task-btn');
            if (deleteBtn) {
                deleteBtn.addEventListener("click", (e) => {
                    e.preventDefault();  // Блокируем стандартное поведение details (раскрытие)
                    e.stopPropagation(); // Блокируем всплытие события (чтобы не сработал click на самом summary)

                    const deleteId = deleteBtn.getAttribute('data-delete-id');
                    if (deleteId) {
                        // Отправляем в Kotlin команду на удаление
                        window.taskBridge?.send("delete:" + deleteId);
                    }
                });
            }

            const exportBtn = el.querySelector('.export-task-btn');
            if (exportBtn) {
                exportBtn.addEventListener("click", (e) => {
                    e.preventDefault();
                    e.stopPropagation(); // Чтобы details не закрывался

                    const taskId = exportBtn.getAttribute('data-task-id');
                    if (taskId) {
                        // Отправляем команду экспорта напрямую в Kotlin
                        window.taskBridge?.send("export:" + taskId);
                    }
                });
            }

        });
    }

    function initObserver() {
        if (!document.body) return;

        const observer = new MutationObserver(attachTaskHandlers);
        observer.observe(document.body, { childList: true, subtree: true });

        attachTaskHandlers();
    }

    // 👉 Ждём DOM
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initObserver);
    } else {
        initObserver();
    }
})();

/**
 * 2. Работа с выделением текста (Selection)
 */
window.getChatSelection = function() {
    const selection = window.getSelection().toString().trim();
    if (selection.length > 0) {
        // Вместо ошибки вставляем метку для замены
        PLACEHOLDER_FOR_INJECT
    }
    return selection;
};

document.addEventListener('mouseup', function() {
    setTimeout(() => window.getChatSelection(), 100);
});