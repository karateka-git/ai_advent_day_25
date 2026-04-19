# local-document-indexer

Учебный Kotlin-проект, выросший из `day_22`.

Если в `day_22` проект уже умел:

- индексировать корпус;
- искать чанки по embeddings;
- отвечать в режимах `plain` и `rag`;
- сравнивать baseline RAG с `plain`;

то в `day_23` основной фокус смещён на **второй этап retrieval**:

- post-retrieval filtering;
- heuristic reranking;
- model-based reranking;
- сравнение режимов на контрольных вопросах.

## Что добавлено в этой сессии

В проект внедрён единый post-retrieval pipeline после первичного vector search.

Новый пайплайн выглядит так:

`query -> embedding search -> topK before -> post-processing -> topK after -> prompt -> LLM`

Добавлены режимы:

- `none`
- `threshold-filter`
- `heuristic-filter`
- `heuristic-rerank`
- `model-rerank`

Также добавлены:

- `initialTopK` и `finalTopK`;
- типизированные причины решений pipeline;
- ранги кандидатов `initialRank/finalRank`;
- runtime-переключение post-mode внутри CLI;
- debug-вывод полного списка кандидатов;
- новый evaluation-скрипт и два отчёта:
  - краткий `summary`;
  - подробный `raw`.

## Режимы post-retrieval

### `none`

Baseline-поведение без второго этапа.

### `threshold-filter`

Отсекает кандидатов ниже similarity-порога и затем оставляет `finalTopK`.

### `heuristic-filter`

Rule-based фильтр. Использует несколько explainable-сигналов:

- cosine similarity;
- lexical overlap;
- exact match;
- title/section match;
- duplicate check.

Коротко по роли сигналов:

- `cosine similarity` — базовый embedding-score после первого этапа поиска;
- `lexical overlap` — пересечение значимых слов вопроса и текста чанка;
- `exact match` — бонус, если значимые слова вопроса встретились прямо в тексте чанка;
- `title/section match` — дополнительные бонусы за совпадения в метаданных;
- `duplicate check` — отсев почти одинаковых чанков, чтобы не тратить `finalTopK` на повторы.

Сейчас `heuristic-filter` не переставляет кандидатов, а только решает, кого оставить в финальном контексте.

### `heuristic-rerank`

Не только фильтрует, а заново сортирует кандидатов по `heuristicScore`.

Это сейчас самый сильный rule-based режим в проекте.

### `model-rerank`

Использует тот же внешний LLM API, что и генерация ответа.

Модель получает:

- `query`
- `title`
- `section`
- `chunk text`

И возвращает JSON вида:

```json
{"score": 83}
```

После этого кандидаты пересортируются по `modelScore`, а `cosineScore` используется как tie-breaker.

## Как переключать режимы

### Разовый override для команды

```text
ask --query "..." --mode rag --strategy structured --top 3 --post-mode heuristic-rerank
```

Поддерживаются:

- `none`
- `threshold-filter`
- `heuristic-filter`
- `heuristic-rerank`
- `model-rerank`

### Session override внутри CLI

```text
set --post-mode none
set --post-mode heuristic-rerank
set --post-mode model-rerank
set --post-mode config
```

`config` сбрасывает override и возвращает режим из `application.conf`.

## Обычный и debug-вывод

По умолчанию CLI показывает только финальные выбранные чанки.

Если нужен полный разбор retrieval pipeline, можно включить debug-флаг:

```text
ask --query "..." --mode rag --strategy structured --top 3 --post-mode model-rerank --show-all-candidates
```

Это удобно для анализа:

- кто попал в `initialTopK`;
- кто был отфильтрован;
- кто поднялся после rerank;
- какие `decisionReason` сработали.

## Evaluation

Для сравнения режимов добавлен сценарий:

- [scripts/run-rag-evaluation.ps1](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_23/scripts/run-rag-evaluation.ps1)

Он:

1. Индексирует корпус `docs/articles/doroshevich`.
2. Прогоняет контрольные вопросы в режимах:
   - `plain`
   - `rag + none`
   - `rag + threshold-filter`
   - `rag + heuristic-filter`
   - `rag + heuristic-rerank`
   - `rag + model-rerank`
3. Строит два отчёта:
   - [data/rag-evaluation-summary.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_23/data/rag-evaluation-summary.md)
   - [data/rag-evaluation-raw.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_23/data/rag-evaluation-raw.md)

Подробное описание формата лежит в:

- [docs/rag-evaluation.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_23/docs/rag-evaluation.md)

## Краткие результаты сравнения режимов

По итогам полного evaluation на 10 контрольных вопросах:

- `Plain`: `0 success / 1 partial / 9 fail`
- `RAG None`: `3 / 0 / 7`
- `Threshold Filter`: `3 / 0 / 7`
- `Heuristic Filter`: `3 / 0 / 7`
- `Heuristic Rerank`: `4 / 0 / 6`
- `Model Rerank`: `3 / 2 / 5`

По числу побед по вопросам:

- `none`: `7`
- `threshold-filter`: `7`
- `heuristic-filter`: `8`
- `heuristic-rerank`: `9`
- `model-rerank`: `9`

Короткий вывод:

- `threshold-filter` почти не улучшает baseline;
- `heuristic-filter` помогает, но иногда режет слишком агрессивно;
- `heuristic-rerank` сейчас выглядит самым сильным и стабильным режимом;
- `model-rerank` полезен на части сложных вопросов, но не всегда лучше эвристического реранка.

## Быстрый запуск

### 1. Сборка и запуск CLI

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-manual-check.ps1
```

### 2. Построить индекс

```text
index --input ./docs/articles/doroshevich --strategy structured
```

### 3. Проверить один режим

```text
ask --query "В чём проклятие Агасфера и почему слово Иди так важно в этом тексте?" --mode rag --strategy structured --top 3 --post-mode heuristic-rerank
```

### 4. Запустить полный evaluation

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-rag-evaluation.ps1
```

## Полезные артефакты

- [docs/control-questions.json](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_23/docs/control-questions.json)
- [docs/control-questions.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_23/docs/control-questions.md)
- [docs/rag-evaluation.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_23/docs/rag-evaluation.md)
- [data/rag-evaluation-summary.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_23/data/rag-evaluation-summary.md)
- [data/rag-evaluation-raw.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_23/data/rag-evaluation-raw.md)

## Что важно помнить

Текущий `day_23` не про новый storage или новый UI. Его основная задача в рамках этой сессии:

- усилить retrieval вторым этапом;
- сделать это в нескольких вариантах;
- дать удобный способ переключать режимы;
- показать сравнение режимов на одном и том же наборе вопросов.
