Application Service (PluginSettings): Один на всю IDE. Хранит API-ключ, общие URL.

Project Service (ProjectSpecificSettings): Свой для каждого открытого окна (проекта). Хранит stub, кастомные промпты для проекта.

Project Service (PluginConfigProvider): "Клей", который создается для конкретного проекта, берет данные из обоих сервисов выше и выдает готовый URL/Body.