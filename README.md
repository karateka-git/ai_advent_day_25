# local-document-indexer

Учебный Kotlin-проект для локальной индексации документов и первого end-to-end RAG-сценария.

Сейчас проект умеет:

- загружать локальный корпус документов;
- извлекать из него текст;
- разбивать текст на чанки двумя стратегиями;
- получать embeddings через локальный `Ollama`;
- сохранять индекс в `SQLite`;
- выполнять semantic search через `SQLite + cosine similarity`;
- отвечать в двух режимах:
  - `plain` — вопрос напрямую в LLM;
  - `rag` — вопрос -> retrieval релевантных чанков -> prompt с контекстом -> LLM;
- сравнивать качество `plain` и `rag` на наборе из 10 контрольных вопросов.

## Что изменилось в проекте

Изначально проект был учебным индексатором документов. В текущей версии поверх этого индекса добавлены:

- `search`-слой:
  - `SearchEngine`
  - `BruteForceSearchEngine`
  - `CosineSimilarity`
- чтение сохранённых embeddings обратно из `SQLite`;
- внешний LLM-клиент по мотивам проекта `day_6`;
- `ask --mode plain`;
- `ask --mode rag`;
- retrieval-сводка в CLI;
- набор из 10 контрольных вопросов;
- evaluation-скрипт и итоговый отчёт сравнения качества.

Иными словами, проект эволюционировал из:

- `документы -> chunking -> embeddings -> SQLite`

в:

- `документы -> chunking -> embeddings -> SQLite -> semantic search -> RAG -> evaluation`

## Стек

- Kotlin
- Gradle
- Ktor Client
- kotlinx.serialization
- SQLite (`sqlite-jdbc`)
- Apache PDFBox
- Ollama
- внешний LLM API для генерации ответа

## Корпус документов

Для проекта используется русский текстовый корпус на основе книги Власа Дорошевича **«Легенды и сказки Востока»**.

Корпус лежит в:

- `docs/articles/doroshevich/`

Состав корпуса:

- `aden.txt`
- `agasfer.txt`
- `bez-allaha.txt`
- `bosfor.txt`
- `reforma.txt`
- `rozhdestvo-hrista.txt`
- `videnie-moiseya.txt`
- `zheleznoe-serdtse.txt`

Суммарный объём:

- около `73 000+` символов текста;
- примерно `40` страниц при грубой оценке `1800` символов на страницу.

Источники текстов:

- Викитека / public domain

## Конфигурация

### Общий конфиг

Безопасные настройки лежат в:

- `src/main/resources/application.conf`

Там хранятся:

- пути проекта;
- chunking-параметры;
- `topK`;
- базовые настройки LLM;
- базовые настройки `Ollama`.

### Локальные секреты и endpoint-настройки

Чувствительные значения вынесены в локальный конфиг:

- `config/app_secrets.conf`

Шаблон:

- `config/app_secrets_example.conf`

Пример:

```hocon
llm {
  agentId = "your-agent-id"
  userToken = "your-token"
}

ollama {
  baseUrl = "your-ollama-base-url"
  embeddingModel = "your-ollama-embedding-model"
}
```

`config/app_secrets.conf` добавлен в `.gitignore`.

## Что нужно перед запуском

### 1. Поднять Ollama для embeddings

Нужно установить и запустить `Ollama`, затем скачать embedding-модель:

```powershell
ollama pull nomic-embed-text
```

Проверка:

```powershell
curl http://localhost:11434/api/tags
```

Важно:

- `plain` не зависит от `Ollama`;
- `search` и `rag` зависят от `Ollama`, потому что им нужен embedding вопроса.

### 2. Заполнить локальный secret-конфиг

Создай `config/app_secrets.conf` на основе `config/app_secrets_example.conf` и подставь:

- `llm.agentId`
- `llm.userToken`
- при необходимости локальные значения `ollama.baseUrl` и `ollama.embeddingModel`

## Сборка и запуск

### Правильный сценарий ручной проверки

По правилам проекта предпочтителен запуск через:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-manual-check.ps1
```

Это:

- собирает проект;
- выполняет `installDist`;
- подготавливает direct launcher;
- открывает CLI для ручной проверки.

Если проект уже собран:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-manual-check.ps1 -SkipBuild
```

### Прямой запуск launcher

```powershell
.\build\install\ai_advent_day_22\bin\local-document-indexer.bat
```

## Доступные команды

```text
index --input ./docs --strategy fixed
index --input ./docs --strategy structured
index --input ./docs --all-strategies
compare --input ./docs
search --query "..." --strategy <fixed|structured> --top <N>
ask --query "..." --mode plain
ask --query "..." --mode rag --strategy <fixed|structured> --top <N>
help
exit
```

Важно:

- `<dir>`, `<N>`, `<text>` в help — это шаблоны, их нельзя вводить буквально;
- нужно подставлять реальные значения, например `./docs` или `3`.

## Как сейчас работает проект

### 1. Индексация

Команда:

```text
index --input ./docs/articles/doroshevich --strategy fixed
index --input ./docs/articles/doroshevich --strategy structured
```

Что происходит:

1. Сканируется входная директория.
2. Загружаются документы.
3. Извлекается и нормализуется текст.
4. Текст режется на чанки.
5. Для каждого чанка строится embedding.
6. Документы, чанки и embeddings сохраняются в `SQLite`.

Артефакты:

- `data/index-fixed.db`
- `data/index-structured.db`

### 2. Semantic search

Команда:

```text
search --query "Как описан Аден?" --strategy structured --top 3
```

Что происходит:

1. Вопрос превращается в embedding через `Ollama`.
2. Из `SQLite` читаются embeddings всех чанков выбранной стратегии.
3. Для каждого чанка считается `cosine similarity`.
4. Результаты сортируются по убыванию score.
5. Пользователь получает `topK` результатов.

### 3. Plain-режим

Команда:

```text
ask --query "Как описан Аден?" --mode plain
```

Что происходит:

- вопрос сразу отправляется во внешний LLM;
- retrieval не используется;
- ответ может быть правдоподобным, но не обязан быть привязан к корпусу.

### 4. RAG-режим

Команда:

```text
ask --query "Как описан Аден?" --mode rag --strategy structured --top 3
```

Что происходит:

1. Вопрос превращается в embedding.
2. Из индекса выбранной стратегии читаются чанки и embeddings.
3. Считается `cosine similarity`.
4. Берутся `topK` самых релевантных чанков.
5. Эти чанки подставляются в prompt.
6. Prompt отправляется во внешний LLM.
7. Пользователь получает:
   - итоговый ответ;
   - retrieval-сводку со `score`, `title`, `filePath`, `section`, `preview`.

## Как сейчас собирается prompt в RAG-режиме

Текущая реализация находится в:

- [RagQuestionAnsweringService.kt](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_22/src/main/kotlin/ru/compadre/indexer/qa/RagQuestionAnsweringService.kt)

В запрос к LLM отправляются два сообщения.

### System prompt

```text
Ты полезный ассистент. Отвечай по контексту из retrieval. Если данных недостаточно, прямо скажи об этом. Не выдумывай факты вне контекста.
```

### User prompt

Структура пользовательского prompt сейчас такая:

```text
Контекст:
Источник 1:
score = ...
title = ...
filePath = ...
section = ...
text = ...

Источник 2:
score = ...
title = ...
filePath = ...
section = ...
text = ...

...

Вопрос:
<текст вопроса>
```

Если retrieval ничего не нашёл, то вместо списка источников идёт:

```text
Контекст:
Контекст не найден.

Вопрос:
<текст вопроса>
```

Именно поэтому текущее качество `rag` полностью зависит от того, насколько retrieval смог поднять правильный чанк в `topK`.

## Что хранится в индексе

`SQLite`-индекс хранит:

- `documents`
- `chunks`
- `embeddings`

Для чанка сохраняются:

- `chunkId`
- `documentId`
- `sourceType`
- `filePath`
- `title`
- `section`
- `startOffset`
- `endOffset`
- `strategy`
- `text`

Для embedding сохраняются:

- `model`
- `vector_json`
- `vector_size`

## Сравнение fixed и structured chunking

Отдельное сравнение chunking-стратегий всё ещё доступно через:

```text
compare --input ./docs/articles/doroshevich
```

Базовые метрики:

- `fixed`:
  - количество чанков: `76`
  - средняя длина чанка: `1153`
  - минимальная длина: `303`
  - максимальная длина: `1200`

- `structured`:
  - количество чанков: `105`
  - средняя длина чанка: `709`
  - минимальная длина: `229`
  - максимальная длина: `1200`

Короткий вывод:

- `fixed` даёт меньше чанков и держится ближе к целевому размеру;
- `structured` лучше сохраняет смысловые границы;
- именно поэтому `structured` выглядит более перспективным кандидатом для RAG.

## Контрольные вопросы

В проекте подготовлен мини-набор из 10 контрольных вопросов:

- [control-questions.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_22/docs/control-questions.md)

Для каждого вопроса зафиксированы:

- `expectation`
- `expectedSources`

Дополнительно есть:

- [control-questions-source-notes.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_22/docs/control-questions-source-notes.md)
- [control-questions.json](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_22/docs/control-questions.json)

## Как сравнивались plain и RAG

Для evaluation был добавлен скрипт:

- [run-rag-evaluation.ps1](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_22/scripts/run-rag-evaluation.ps1)

Он:

1. индексирует корпус `docs/articles/doroshevich` отдельно в `fixed` и `structured`;
2. прогоняет 10 контрольных вопросов в трёх режимах:
   - `plain`
   - `rag-fixed`
   - `rag-structured`
3. сохраняет сырой отчёт в `data/rag-evaluation.md`.

Итоговый краткий отчёт лежит в:

- [rag-evaluation.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_22/docs/rag-evaluation.md)

## Краткие результаты сравнения plain и RAG

Ключевые наблюдения:

- `plain` часто даёт уверенный, но не привязанный к корпусу ответ;
- `rag` лучше дисциплинирует модель и заставляет её опираться на найденный контекст;
- если retrieval промахивается, `rag` тоже не спасает;
- `structured` retrieval в среднем лучше `fixed`, но преимущество пока нестабильное.

Формальные сигналы по retrieval:

- `fixed`: ожидаемый источник в `top1` у `3/10` вопросов
- `fixed`: ожидаемый источник в `top3` у `5/10` вопросов
- `structured`: ожидаемый источник в `top1` у `5/10` вопросов
- `structured`: ожидаемый источник в `top3` у `5/10` вопросов

Примеры:

- `plain` на вопросе про цвета Адена выдумывает палитру, которой нет в тексте;
- `rag` на вопросе про аденские цистерны даёт ответ по корпусу;
- `rag-structured` на вопросе про Агасфера находит нужный фрагмент со словом «Иди!»;
- на части вопросов retrieval уводит в нерелевантные тексты, и это главный текущий bottleneck проекта.

## Быстрый сценарий проверки

### 1. Построить индексы

```text
index --input ./docs/articles/doroshevich --strategy fixed
index --input ./docs/articles/doroshevich --strategy structured
```

### 2. Проверить retrieval

```text
search --query "Как в тексте «Аден» описываются основные цвета города и берега?" --strategy fixed --top 3
search --query "Как в тексте «Аден» описываются основные цвета города и берега?" --strategy structured --top 3
```

### 3. Сравнить plain и rag

```text
ask --query "Как в тексте «Аден» описываются основные цвета города и берега?" --mode plain
ask --query "Как в тексте «Аден» описываются основные цвета города и берега?" --mode rag --strategy fixed --top 3
ask --query "Как в тексте «Аден» описываются основные цвета города и берега?" --mode rag --strategy structured --top 3
```

### 4. Проверить вопрос из контрольного набора

```text
ask --query "В чём проклятие Агасфера и почему слово «Иди!» так важно в этом тексте?" --mode plain
ask --query "В чём проклятие Агасфера и почему слово «Иди!» так важно в этом тексте?" --mode rag --strategy fixed --top 3
ask --query "В чём проклятие Агасфера и почему слово «Иди!» так важно в этом тексте?" --mode rag --strategy structured --top 3
```

## Артефакты проекта

После работы проекта могут появляться:

- `data/index-fixed.db`
- `data/index-structured.db`
- `data/comparison.md`
- `data/rag-evaluation.md`

Документационные артефакты:

- `docs/control-questions.md`
- `docs/control-questions-source-notes.md`
- `docs/control-questions.json`
- `docs/rag-task-spec.md`
- `docs/rag-evaluation.md`

## Что пока не входит в проект

Пока не реализованы:

- `FAISS`
- reranking retrieval-результатов
- hybrid search
- web UI

Архитектура при этом остаётся `SQLite-first`, чтобы следующим шагом можно было либо усиливать brute-force retrieval, либо добавлять `FAISS` как отдельный search backend.
