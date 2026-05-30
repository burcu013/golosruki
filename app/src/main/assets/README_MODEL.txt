Сюда нужно положить папку модели Vosk: model-ru/

Если собираете через GitHub Actions — модель скачивается автоматически в workflow,
этот файл и ручные действия не нужны.

Если собираете локально:
1. Скачайте vosk-model-small-ru-0.22 с https://alphacephei.com/vosk/models
2. Распакуйте, переименуйте папку в model-ru и поместите её рядом с этим файлом:
   app/src/main/assets/model-ru/am/...
   app/src/main/assets/model-ru/conf/...
   и т.д.
