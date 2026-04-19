# Evaluation RAG-режимов

Этот документ описывает текущий формат `evaluation` для `ask --mode rag` после добавления обязательных `sources`, `quotes` и режима честного отказа.

`README.md` здесь не меняется. Этот файл нужен как короткая рабочая инструкция для понимания отчёта и его сигналов.

## Что делает скрипт

Сценарий `scripts/run-rag-evaluation.ps1`:

1. Индексирует корпус для выбранной стратегии chunking.
2. Для каждого контрольного вопроса запускает:
   - `plain`
   - `rag + none`
   - `rag + threshold-filter`
   - `rag + heuristic-filter`
   - `rag + heuristic-rerank`
   - `rag + model-rerank`
3. Для каждого RAG-ответа отдельно извлекает:
   - `answer body`
   - `sources block`
   - `quotes block`
   - `retrieval summary`
4. Считает простой учебный `verdict`:
   - `success`
   - `partial`
   - `fail`
5. Формирует два markdown-отчёта:
   - summary report
   - raw report

## Что проверяется

Evaluation теперь смотрит не только на retrieval, но и на сам формат ответа.

### 1. Есть ли источники

Проверяется, что в ответе присутствует непустой `sources block`, а не только текст ответа.

Для этого скрипт смотрит, есть ли в блоке строки вида:

- `source = ...`
- `section = ...`
- `chunkId = ...`

### 2. Есть ли цитаты

Проверяется, что в ответе есть непустой `quotes block`.

Для этого скрипт смотрит, есть ли хотя бы одна цитата с:

- `chunkId = ...`
- `quote = ...`

### 3. Есть ли смысловое пересечение ответа и цитат

Это MVP-эвристика, не строгая метрика.

Скрипт:

1. Берёт термы из `answer body`.
2. Берёт термы из текста цитат.
3. Считает overlap значимых терминов.

Если overlap слишком слабый, verdict не поднимается до `success`.

### 4. Похоже ли это на refusal

Скрипт отдельно отмечает, выглядит ли ответ как честный отказ:

- `не знаю`
- `уточните вопрос`
- `данных недостаточно`
- `контекст не найден`

Это важно, потому что refusal не должен выглядеть как обычный ответ, и наоборот.

## Как устроен verdict

`verdict` остаётся простым и учебным, без академической претензии.

- `success` - ответ grounded, есть источники, есть цитаты, и цитаты заметно пересекаются с ответом.
- `partial` - есть полезный сигнал, но один из ключевых критериев слабый или отсутствует.
- `fail` - структура ответа слабая, источники/цитаты отсутствуют, или ответ уходит от retrieval.

Отдельно в отчёте отмечается, если refusal выглядит разумным при слабом retrieval.

## Summary report

`data/rag-evaluation-summary.md` - это основной отчёт для быстрого чтения.

Для каждого вопроса он показывает:

- формулировку вопроса;
- ожидание и ожидаемые источники;
- таблицу по режимам с колонками:
  - `verdict`
  - `score`
  - `sources`
  - `quotes`
  - `align`
  - `refusal`
  - `note`
- краткие счётчики по вопросу:
  - сколько режимов дали `success`
  - сколько дали `partial`
  - сколько ответов содержали `sources`
  - сколько содержали `quotes`
  - сколько прошли `quote alignment`
  - сколько выглядели как refusal

## Raw report

`data/rag-evaluation-raw.md` нужен для отладки.

Для каждого режима в нём видны отдельные блоки:

- `Answer`
- `Sources`
- `Quotes`
- `Retrieval`

Это удобно, когда нужно понять, почему verdict получился именно таким.

## Как запускать

Обычно порядок такой:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-manual-check.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\run-rag-evaluation.ps1
```

После этого смотрим:

- `data/rag-evaluation-summary.md`
- `data/rag-evaluation-raw.md`

## Operational note

Full evaluation only works when the external LLM agent is active and reachable. If the agent returns `403` with `code=agent_not_active`, start or reactivate that agent before rerunning the script.

The local RAG stack also has to be up: the embedding/Ollama service used by indexing and retrieval must be available, otherwise the evaluation will fail before the reports are written.

## Как интерпретировать результат

Для учебного проекта полезно смотреть не только на лучший режим, но и на то, где ломается контракт ответа.

Если хороший `retrieval` есть, а `sources` или `quotes` отсутствуют, это уже сигнал к доработке слоя ответа.

Если `refusal` появляется там, где retrieval явно слабый, это может быть нормальным и даже желательным поведением.

Если `answer` и `quotes` почти не пересекаются по термам, значит ответ выглядит недостаточно grounded, даже если формально всё было заполнено.
