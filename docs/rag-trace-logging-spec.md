# ТЗ: поэтапное внедрение полного trace-логирования RAG pipeline

## Контекст

В проекте `day_24` уже есть рабочий RAG pipeline:

- пользовательский запрос;
- embedding retrieval;
- post-retrieval этап;
- `model-rerank`;
- answer guard;
- генерация RAG-ответа;
- источники и цитаты.

Сейчас для диагностики у проекта есть только частичные средства:

- CLI-вывод для пользователя;
- evaluation-отчёты;
- точечный лог проблем формата ответа модели.

Этого недостаточно для точного анализа, где именно ломается качество ответа:

- на этапе первичного embedding search;
- на этапе `model-rerank`;
- на этапе answer generation;
- на этапе извлечения цитаты;
- на этапе guard/refusal.

В качестве референса нравится подход из `day_14`, где используется отдельный `DebugTraceListener`, записывающий структурированный trace независимо от CLI.

Нужно сделать похожий по духу механизм для `day_24`, но с учётом текущей архитектуры проекта.

---

## Главная цель

Построить в `day_24` систему trace-логирования, которая:

1. в MVP логирует ключевые этапы RAG pipeline;
2. не загрязняет пользовательский CLI-вывод;
3. сохраняет trace в машинно-читаемом формате;
4. легко расширяется до полного сырого лога всех шагов pipeline.

---

## Основная идея

Trace должен быть:

- отдельным от UI;
- структурированным;
- по возможности сырым;
- пригодным как для ручного чтения, так и для автоматического анализа.

Рекомендуемый формат:

- `jsonl`

Рекомендуемый файл:

- `data/logs/rag-trace.jsonl`

Подход:

- каждый важный шаг pipeline пишет отдельное событие;
- запись делается через единый sink/listener;
- later мы расширяем набор событий, не ломая формат и интеграцию.

---

## Архитектурный принцип

Референс из `day_14` показывает правильный шаблон:

- есть отдельный trace-listener/sink;
- есть структура trace record;
- UI и debug trace не смешиваются;
- одна запись trace не равна одной строке пользовательского вывода.

Для `day_24` это означает:

- не логировать через `println`;
- не пытаться вытягивать trace из formatter-а;
- не строить trace как побочный эффект CLI.

Нужно внедрять именно **внутреннюю модель событий pipeline**.

---

## Целевой результат

После реализации trace-логирования по одному RAG-запросу можно будет увидеть:

- сам запрос пользователя;
- initial candidates после embedding search;
- model-rerank score по каждому кандидату;
- финально выбранные чанки;
- результат answer guard;
- prompt к answer-LLM;
- raw completion answer-LLM;
- итоговый RAG-ответ;
- причины fallback/refusal при наличии.

В будущем этот же механизм должен расширяться до:

- полного сырого логирования всех pipeline-шагов;
- таймингов этапов;
- token usage;
- session-level trace;
- search/index traces;
- LLM prompt/response traces не только для answer, но и для rerank.

---

# Этапы реализации

## Этап 1. Ввести базовую trace-инфраструктуру

### Цель

Создать минимальный, но расширяемый каркас trace-логирования.

### Что нужно сделать

1. Ввести модель trace-записи.

   Например:

   - `timestamp`
   - `requestId`
   - `kind`
   - `stage`
   - `payload`

2. Ввести интерфейс sink/listener.

   Например:

   - `TraceSink`
   - `emit(record: TraceRecord)`

3. Реализовать JSONL sink.

   Например:

   - `JsonlTraceSink`

4. Добавить no-op реализацию.

   Например:

   - `NoOpTraceSink`

### Требования

- запись должна быть потокобезопасной;
- директория логов должна создаваться автоматически;
- trace не должен ломать выполнение основного pipeline;
- при ошибке записи trace основной pipeline должен продолжать работу.

### Предполагаемые файлы

- новый пакет, например:
  - `src/main/kotlin/ru/compadre/indexer/trace/`
- возможные файлы:
  - `TraceRecord.kt`
  - `TraceSink.kt`
  - `JsonlTraceSink.kt`
  - `NoOpTraceSink.kt`

---

## Этап 2. Логировать MVP-набор событий для RAG

### Цель

Получить минимально полезный trace именно для анализа проблем с retrieval и rerank.

### MVP-события

#### 1. `rag_request_started`

Что логировать:

- `query`
- `mode`
- `strategy`
- `topK`
- `postMode`

#### 2. `embedding_candidates_built`

Что логировать:

- `initialTopK`
- список initial candidates
- для каждого кандидата:
  - `chunkId`
  - `title`
  - `section`
  - `filePath`
  - `cosineScore`
  - `initialRank`

#### 3. `model_rerank_scored`

Что логировать:

- `query`
- `chunkId`
- `title`
- `section`
- `cosineScore`
- `modelScore`
- `usedFallback`
- `rawResponse`

#### 4. `selected_matches_built`

Что логировать:

- `finalTopK`
- список selected matches
- `finalRank`
- `chunkId`
- `cosineScore`
- `modelScore`

#### 5. `answer_guard_checked`

Что логировать:

- `selectedCount`
- `topScore`
- `allowed`
- `reason`

#### 6. `answer_llm_completed`

Что логировать:

- `question`
- `prompt`
- `rawCompletion`

#### 7. `rag_answer_built`

Что логировать:

- `answer`
- `sources`
- `quotes`
- `isRefusal`
- `refusalReason`
- `warningMessage`

### Предполагаемые точки интеграции

- `DefaultWorkflowCommandHandler`
- `RetrievalPipelineService`
- `ModelRerankJudge`
- `RagQuestionAnsweringService`

### Важное ограничение MVP

На этом этапе не нужно логировать “всё подряд”.  
Нужно покрыть минимальный маршрут анализа:

- был ли нужный чанк в initial pool;
- что сделал `model-rerank`;
- что выбрал pipeline;
- что произошло при answer generation.

---

## Этап 3. Прокинуть `requestId` через весь pipeline

### Цель

Связать все события одного пользовательского запроса в одну цепочку.

### Что нужно сделать

1. Вводить `requestId` в начале `ask/search` запроса.
2. Передавать его во все trace-события.
3. Использовать один `requestId` для:
   - retrieval;
   - rerank;
   - answer generation;
   - итогового ответа.

### Почему это важно

Без `requestId` trace превращается в общий шумный лог.

С `requestId` можно:

- выделить одну полную цепочку;
- сравнить несколько прогонов одного вопроса;
- находить “на каком этапе сломался именно этот запрос”.

---

## Этап 4. Подготовить каркас для полного сырого trace

### Цель

Не останавливаться на MVP-логировании, а сразу заложить расширяемую модель.

### Что нужно предусмотреть уже сейчас

1. Гибкую модель `payload`.

   Лучше не жёсткий список полей в корне, а структурированный payload.

2. События должны быть независимыми от CLI formatter-а.

3. Trace должен быть пригоден и для `search`, и для `ask`, и для будущего `index`.

4. Желательно предусмотреть поле `stage`, например:

- `workflow`
- `retrieval`
- `model-rerank`
- `answer-guard`
- `answer-llm`
- `result`

### Что later можно будет добавить без переделки архитектуры

- `search_request_started`
- `index_started`
- `index_finished`
- `embedding_generated`
- `llm_request_started`
- `llm_request_finished`
- `token_usage`
- `duration_ms`
- `raw_http_request`
- `raw_http_response`

---

## Этап 5. Расширить trace до полного сырого pipeline-лога

### Это уже не MVP, а следующий горизонт

Именно сюда хочется прийти в будущем:

- логировать все шаги pipeline;
- логировать их в максимально сыром виде;
- не ограничиваться тем, что сейчас видно пользователю в CLI.

### Что должно войти в полную версию

1. Сырые входы и выходы rerank-LLM.
2. Сырые входы и выходы answer-LLM.
3. Все intermediate candidates.
4. Тайминги этапов.
5. Причины fallback.
6. Parse result и parse failure.
7. Guard decisions.
8. При необходимости — search/index session trace.

### Отдельная мысль

Полная версия trace должна быть ближе к internal execution log, чем к user-facing report.

То есть trace — это **не второй CLI**, а **сырой журнал исполнения pipeline**.

---

# Как это ложится на текущий проект

## Ложится хорошо, потому что:

1. В `day_24` уже есть чёткие слои:

- workflow
- retrieval
- rerank
- answer generation

2. Есть естественные точки встраивания:

- `DefaultWorkflowCommandHandler`
- `RetrievalPipelineService`
- `ModelRerankJudge`
- `RagQuestionAnsweringService`
- `ExternalLlmClient` при необходимости later

3. Есть уже понятный целевой сценарий анализа:

- понять, виноват ли initial retrieval;
- понять, виноват ли `model-rerank`;
- понять, что именно произошло в answer generation.

## Что отличается от `day_14`

В `day_14` уже есть явная event-модель приложения и listener abstraction.  
В `day_24` такого слоя пока нет.

Это значит:

- файл из `day_14` нельзя просто “перенести как есть”;
- но архитектурный паттерн переносится очень хорошо;
- в `day_24` сначала нужно ввести собственный минимальный `TraceSink`.

---

# Нефункциональные требования

1. Trace не должен ломать основной pipeline.
2. Trace не должен засорять CLI.
3. Формат должен быть пригоден для автоматического анализа.
4. Формат должен быть расширяемым без больших миграций.
5. MVP не должен превращаться в большой рефакторинг всего проекта.

---

# Предлагаемый порядок реализации

1. Этап 1: базовая trace-инфраструктура.
2. Этап 2: MVP-события для RAG pipeline.
3. Этап 3: `requestId` через всю цепочку.
4. Этап 4: подготовка формата под future full trace.
5. Этап 5: постепенное расширение до полного сырого журнала.

---

# Критерии готовности MVP

MVP считается выполненным, если по одному RAG-запросу можно восстановить:

1. сам вопрос пользователя;
2. initial candidates после embedding search;
3. оценку каждого кандидата на этапе `model-rerank`;
4. финально выбранные чанки;
5. решение guard;
6. raw completion answer-LLM;
7. итоговый RAG-ответ.

---

# Короткий смысл решения

Нужно не просто “добавить ещё логов”, а ввести в `day_24` отдельную trace-подсистему по образцу `day_14`:

- структурированную;
- независимую от CLI;
- jsonl-ориентированную;
- расширяемую до полного сырого лога всех шагов pipeline.
