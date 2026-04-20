# Шаблон отчёта по проверке mini-chat с RAG и памятью задачи

Дата проверки:

- `YYYY-MM-DD`

Проверяющий:

- `<имя>`

Использованный launcher:

- [local-document-indexer.bat](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_25/build/install/ai_advent_day_25/bin/local-document-indexer.bat)

Использованная команда chat:

```text
chat --strategy structured --top 3
```

Использованный индекс:

```text
index --input ./docs/articles/doroshevich --strategy structured
```

---

## Общий итог

- Сценарий 1: `<pass|partial|fail>`
- Сценарий 2: `<pass|partial|fail>`
- Негативные кейсы: `<pass|partial|fail>`

Короткий вывод:

- `<1-5 предложений>`

---

## Сценарий 1. Удержание цели и ограничений

Источник сценария:

- [chat-evaluation-scenarios.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_25/docs/chat-evaluation-scenarios.md)

### Таблица прогона

| Turn | User input | Actual TaskState delta | Retrieval action | Result type | Sources | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 1 |  |  |  |  |  |  |
| 2 |  |  |  |  |  |  |
| 3 |  |  |  |  |  |  |
| 4 |  |  |  |  |  |  |
| 5 |  |  |  |  |  |  |
| 6 |  |  |  |  |  |  |
| 7 |  |  |  |  |  |  |
| 8 |  |  |  |  |  |  |
| 9 |  |  |  |  |  |  |
| 10 |  |  |  |  |  |  |

### Вывод по сценарию

- Цель удержана: `<да|частично|нет>`
- Ограничения удержаны: `<да|частично|нет>`
- Follow-up вопросы поняты: `<да|частично|нет>`
- Источники показывались там, где ожидалось: `<да|частично|нет>`

Комментарий:

- `<свободный текст>`

---

## Сценарий 2. Переопределение ограничений и терминов

Источник сценария:

- [chat-evaluation-scenarios.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_25/docs/chat-evaluation-scenarios.md)

### Таблица прогона

| Turn | User input | Actual TaskState delta | Retrieval action | Result type | Sources | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| 1 |  |  |  |  |  |  |
| 2 |  |  |  |  |  |  |
| 3 |  |  |  |  |  |  |
| 4 |  |  |  |  |  |  |
| 5 |  |  |  |  |  |  |
| 6 |  |  |  |  |  |  |
| 7 |  |  |  |  |  |  |
| 8 |  |  |  |  |  |  |
| 9 |  |  |  |  |  |  |
| 10 |  |  |  |  |  |  |
| 11 |  |  |  |  |  |  |

### Вывод по сценарию

- Ограничения переопределились корректно: `<да|частично|нет>`
- Термины помогали follow-up вопросам: `<да|частично|нет>`
- Смена темы не сломала retrieval: `<да|частично|нет>`
- Источники показывались там, где ожидалось: `<да|частично|нет>`

Комментарий:

- `<свободный текст>`

---

## Негативные кейсы

### Таблица прогона

| Case | Input / Sequence | Expected behavior | Actual behavior | Verdict | Notes |
| --- | --- | --- | --- | --- | --- |
| A | Слабый контекст и refusal | честный отказ |  |  |  |
| B | `:memory` в начале сессии | пустая память |  |  |  |
| C | `:history` в начале сессии | пустая история |  |  |  |
| D | short service turn | `Retrieval пропущен` |  |  |  |

---

## Trace observations

При необходимости сюда можно выписать наблюдения по trace:

- `task_state_updated`
- `retrieval_query_built`
- `retrieval_skipped`
- `chat_turn_completed`

Комментарий:

- `<свободный текст>`

---

## Итоговые замечания

### Что уже хорошо

- `<список или короткие пункты>`

### Что надо поправить

- `<список или короткие пункты>`

### Что проверить повторно после правок

- `<список или короткие пункты>`
