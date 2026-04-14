// taskHandlers.js — чистый JS, без всяких inject
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