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

Real entrypoint использует private receipts API на `mco.nalog.ru` и browser
auth API на отдельном фиксированном FNS origin `lkdr.nalog.gov.ru`. Перед
первым real login установите отдельный runtime Playwright Chromium и запустите
сервер:

```bash
./gradlew playwrightInstall
export RECEIPTS_API_BASE=https://mco.nalog.ru/api
MCP_PORT=3002 ./build/install/receipts-mcp-server/bin/receipts-mcp-server
```

`POST /receipts/browser-login` не принимает body: он запускает видимый Chromium
с временным изолированным контекстом на
`https://lkdr.nalog.gov.ru/login`. Телефон, CAPTCHA и SMS вводит только
пользователь. Код читает только успешный POST
`/api/v2/auth/challenge/phone/start` на точном HTTPS origin и извлекает из его
request body whitelist `deviceInfo` (`appVersion`, `sourceDeviceId`,
`sourceType`, `metaDetails.userAgent`). Для успешного POST
`/api/v1/auth/challenge/phone/verify` читаются только response fields `token`,
`refreshToken`, `tokenExpireIn` и `refreshTokenExpiresIn`; request body
подтверждения, содержащий SMS-код, никогда не читается. CAPTCHA/MFA и anti-bot
защита не автоматизируются; личный browser profile не используется.

Только минимальная сессия хранится в OS credential store как app-specific item
`smart-expense-agent.receipts` / `real-session`; plaintext persistence
отсутствует. `java-keyring` не предоставляет выбора Secret Service collection,
поэтому Linux implementation использует Secret Service API с явным путём
persistent GNOME Keyring collection. `default` alias только читается и никогда
не изменяется.
`java-keyring` используется для остальных платформ.
Ошибка записи keyring не сообщает об успешном login и не заменяет предыдущую сохранённую сессию.
`GET /receipts/session` только сообщает текущее состояние и не выполняет refresh.
Явный refresh запускается через `POST /receipts/session/retry`; private search/get
также один раз обновляют session, если API отклоняет access token.
В обоих случаях фиксированный endpoint
`https://lkdr.nalog.gov.ru/api/v1/auth/token` получает сохранённые `refreshToken`
и whitelist `deviceInfo`; новые токены сохраняются до публикации успешного
состояния. Одновременные запросы с одним отклонённым access token используют
один refresh; private API повторяется не более одного раза и только с
обновлённой авторизацией. Временный сбой refresh сохраняет session и сообщает
`recoverable_error`, а отклонённый refresh token требует ручного browser login.
Когда сохранённые `tokenExpireIn` или пара `token_issued_at_epoch_ms` /
`expires_in_seconds` однозначно указывают истечение, status сообщает
`recoverable_error` до следующего refresh; неизвестный/неразбираемый формат
expiry не интерпретируется. Ни auth client, ни receipt API client не следуют
HTTP redirects: refresh body и bearer token не пересылаются другому host.
Logout удаляет app-specific item из persistent keyring collection.

JSON phone/password/OTP routes оставлены только для deterministic fake server.
Real server отклоняет `/receipts/login` и `/receipts/otp/resend` с HTTP 403 до
чтения request body.

Loopback server не передаёт credentials, auth payload или tokens через MCP
arguments, публичные session responses, application logs или MCP results.
При недоступности keyring состояние явно сообщает `unavailable`.
State-changing auth POST-запросы отклоняются при untrusted `Origin`,
`Sec-Fetch-Site` или несовпадающем loopback `Host`; server-to-server вызовы без
`Origin` остаются допустимыми.

MCP server по-прежнему слушает только loopback; внешний bind address не
поддерживается.

Loopback endpoints:

```text
POST http://127.0.0.1:3002/receipts/browser-login   (no body)
GET  http://127.0.0.1:3002/receipts/session
POST http://127.0.0.1:3002/receipts/session/retry
POST http://127.0.0.1:3002/receipts/logout
POST http://127.0.0.1:3002/receipts/login           (fake mode only)
POST http://127.0.0.1:3002/receipts/otp/resend      (fake mode only)
POST http://127.0.0.1:3002/mcp
```

Session routes return the sanitized `ReceiptSessionResponse` fields:
`authenticated`, `status`, `retryable`, `retry_after_seconds`,
`persistence_status`, and optional `persistence_message` and `message`.
`message` contains only safe state feedback and never auth data. `browser-login`
returns `authenticating` promptly; polling reports the current state. Failed
manual login retains a previously usable session and reports a sanitized message;
without a usable session it reports `login_required`. Status polling does not
refresh.

`MCP_PORT` задаёт порт в диапазоне `1..65535`.


## Fake server

Fake server предназначен для локальной приёмки без сети и секретов. Он
запускается отдельным Gradle target и не является скрытым режимом real
entrypoint:

```bash
MCP_PORT=3002 ./gradlew runFakeServer --no-daemon
```

Synthetic credentials для legacy fake-only lifecycle:

```text
phone:    +79990000000
otp:      000000
password: demo
```

В fake mode `POST /receipts/browser-login` сразу создаёт deterministic fake
session без запуска Chromium и сетевых запросов; сохранены и legacy steps
`phone → otp → password`. Fake session живёт только в памяти процесса.
После успешного login доступны те же `/mcp` и read-only receipts endpoints.

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

Необязательное поле `settlement_place` заполняется только из корневого
`retailPlace` в ответе `/v1/receipt/fiscal_data`; `retailPlaceAddress` не
используется в качестве замены и не публикуется. Расположение поля в ответе
endpoint закреплено только синтетическим HTTP-fixture и не является
независимым подтверждением схемы реального private API.

В официальном формате ФНС `retailPlace` обозначает место расчётов, а
`retailPlaceAddress` — адрес расчётов ([описание формата ФНС](https://www.nalog.gov.ru/html/sites/www.new.nalog.ru/docs/kkt/1_1_141_210321.pdf)).

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

Автотесты используют Ktor `MockEngine` и изолированный route test. Проверяются:

- точные HTTPS origin, POST method и endpoint path для capture; фильтрация
  посторонних host/path/method и response failures;
- whitelist deviceInfo, захват только полей успешного verify response и
  отсутствие чтения OTP verify request body;
- восстановление/refresh с нужными `deviceInfo` и `refreshToken`, status-only
  polling без external refresh, явный retry и logout;
- private search/get обновляют отвергнутый access token через один совместный
  refresh, повторяют запрос один раз с rotated token и не зацикливаются;
- истёкший известный expiry отображается как `recoverable_error`; transient
  refresh failure сохраняет session, а rejection refresh token требует login;
- keyring write failure без ложного успешного login или потери старых данных;
- явный выбор persistent GNOME Keyring collection, reuse app-specific item после
  изменения default alias и отсутствие alias mutation/session-only хранения;
- pre-v30 v1 keyring bytes сохраняются без миграции после restore и неуспешного browser login;
- 3xx refresh/API response не инициирует повторный запрос на redirect target
  с refresh token или bearer credential;
- запрет legacy JSON auth routes в real mode и deterministic network-free fake.

Реальный login с credentials не запускается автотестами. Пользователь вручную
вводит CAPTCHA/SMS в отдельном видимом окне; CAPTCHA, MFA и anti-bot обхода нет.
