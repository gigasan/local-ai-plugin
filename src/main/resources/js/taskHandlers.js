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

            el.addEventListener("click", () => {
                window.taskBridge?.send("click:" + taskId);
            });

            el.addEventListener("mouseenter", () => {
                window.taskBridge?.send("hover:" + taskId);
            });

            el.addEventListener("mouseleave", () => {
                window.taskBridge?.send("hover:exit");
            });
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