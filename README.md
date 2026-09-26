# receipts-mcp-server

Локальный read-only MCP-сервер для просмотра чеков покупателя через
совместимый с «Мои чеки онлайн» private API. Сервис слушает только loopback и
не передаёт credentials в MCP tool arguments или публичные результаты.

## Сборка и тесты

```bash
./gradlew test installDist
```

Запускаемый файл real entrypoint:

```text
build/install/receipts-mcp-server/bin/receipts-mcp-server
```

## Real server

Real entrypoint использует private API и требует явной сессии receipts. До
первого запуска задайте только подтверждённые в вашей среде endpoints:

```bash
export RECEIPTS_API_BASE=https://mco.nalog.ru/api
export RECEIPTS_LOGIN_URL=https://<verified-login-endpoint>
export RECEIPTS_REFRESH_URL=https://<verified-refresh-endpoint>
MCP_PORT=3002 ./build/install/receipts-mcp-server/bin/receipts-mcp-server
```

Если `RECEIPTS_LOGIN_URL` не задан, server не угадывает upstream auth contract и
возвращает безопасный ответ `error`. Login, SMS, CAPTCHA, browser bridge и
anti-bot обход не реализованы. Если upstream требует CAPTCHA или MFA, процесс
останавливается на штатном шаге провайдера.

Сессия хранится в OS credential store через `java-keyring` как versioned JSON
после успешного login. При истечении access token используется только явно
настроенный refresh endpoint; повернутые access/refresh tokens сохраняются
атомарно с точки зрения приложения. При временной ошибке refresh сохранённая
сессия не удаляется, чтобы работал повторный retry. Если credential store
недоступен, API явно сообщает `unavailable` и не делает вид, что сессия
переживёт restart.

Loopback endpoints:

```text
POST http://127.0.0.1:3002/receipts/login
POST http://127.0.0.1:3002/receipts/otp/resend
GET  http://127.0.0.1:3002/receipts/session
POST http://127.0.0.1:3002/receipts/session/retry
POST http://127.0.0.1:3002/receipts/logout
POST http://127.0.0.1:3002/mcp
```

Пример login body — только контракт локального adapter boundary; формат
конкретного upstream должен быть подтверждён отдельно:

```json
{"phone":"+79990000000","password":"...","otp":"..."}
```

`MCP_PORT` задаёт порт в диапазоне `1..65535`; внешний bind address не
поддерживается.

## Fake server

Fake server предназначен для локальной приёмки без сети и секретов. Он
запускается отдельным Gradle target и не является скрытым режимом real
entrypoint:

```bash
MCP_PORT=3002 ./gradlew runFakeServer --no-daemon
```

Synthetic credentials:

```text
phone:    +79990000000
otp:      000000
password: demo
```

Lifecycle fake login: сначала отправьте только `phone`, затем `phone + otp`,
затем `phone + otp + password`. Fake session живёт в памяти процесса. После
успешного login доступны те же `/mcp` и auth endpoints; fake mode не выполняет
сетевых запросов.

Fixtures:

- `receipt-001` — ДЕМО МАРКЕТ, `34900` копеек;
- `receipt-002` — ДЕМО КАФЕ, `127500` копеек;
- `receipt-003` — ДЕМО МАРКЕТ, `8900` копеек.

## MCP tools

`tools/list` публикует два read-only idempotent tools.

### `search-receipts`

Ищет чеки по продавцу и периоду:

```json
{
  "query": "ДЕМО МАРКЕТ",
  "from": "2026-09-01",
  "to": "2026-09-30",
  "limit": 50,
  "offset": 0,
  "order_by": "RECEIVE_DATE:DESC"
}
```

Все поля опциональны. `limit` ограничен диапазоном `1..100`, `offset` —
`0..10000`, даты должны иметь формат `YYYY-MM-DD`. Результат содержит
`receipt_key`, продавца, дату получения, сумму в копейках и `has_more`.

### `get-receipt`

Возвращает детали чека по ключу из `search-receipts`:

```json
{"receipt_key":"receipt-001"}
```

Результат содержит фискальные идентификаторы, итоговую сумму в копейках и
позиции с количеством, ценой, суммой, ставкой НДС и типами оплаты/товара.
Адрес покупателя, ИНН пользователя, provider metadata и неизвестные raw-поля
не возвращаются.

## Private API adapter

Маппинг private API изолирован в `ReceiptService` и покрыт mock HTTP-тестами:

```text
POST https://mco.nalog.ru/api/v1/receipt
POST https://mco.nalog.ru/api/v1/receipt/fiscal_data
```

Список чеков отправляет `dateFrom`, `dateTo`, `kktOwner`, `limit`, `offset` и
`orderBy`. Детализация отправляет `key`. Ответы преобразуются в нормализованный
MCP-контракт. Private API не является официальным стабильным контрактом ФНС и
может измениться без уведомления.

## Проверки

Автотесты используют Ktor `MockEngine` как mock-сервис receipts. Проверяются:

- HTTP method, URL, JSON body и Bearer authorization boundary;
- преобразование списка чеков и фискальных позиций;
- валидация входных параметров до сетевого запроса;
- ошибки upstream и отсутствие секретов в публичных сообщениях;
- fake login lifecycle и запрет чтения до авторизации;
- refresh с ротацией token, восстановление и сохранение сессии при временной
  ошибке;
- отсутствие сетевых вызовов в fake mode.

Реальный smoke-запуск с credentials не входит в обычный запуск тестов. CAPTCHA,
MFA и антибот-защита не обходятся.
