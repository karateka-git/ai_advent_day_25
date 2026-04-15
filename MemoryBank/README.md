# MemoryBank

Локальный банк памяти для проекта `ai_advent_day_21`.

## Структура

- `rules/` - проектные правила и локальные договорённости.
- `notes/` - короткие заметки по целям и текущему направлению проекта.

## Как использовать

- Перед началом нового смыслового блока сначала читать локальный `MemoryBank` проекта.
- После этого сверяться с общим `MemoryBank` в `C:\Users\compadre\Downloads\Projects\MemoryBank`.
- Если в проекте есть краткий operational summary, дополнительно читать [agent-preflight.md](/C:/Users/compadre/Downloads/Projects/AiAdvent/day_21/MemoryBank/agent-preflight.md).

## Что перенесено из day_20

В этот `MemoryBank` вынесены правила, которые полезны и для текущего проекта:

- держать MVP простым и не добавлять лишние абстракции раньше времени;
- выносить настройки CLI и endpoint-подобные параметры в конфиг;
- разделять CLI parsing, orchestration и domain logic;
- документировать новые публичные сущности и важный orchestration-код;
- учитывать UTF-8 как часть рабочего сценария CLI на Windows;
- сохранять проект расширяемым, но не реализовывать future scope раньше времени.

