# receipts-mcp-server

Локальный MCP-сервер чеков для интеграции со
Smart Expense Agent.

## Сборка

```bash
./gradlew installDist
```

Запускаемый файл после сборки:

```text
build/install/receipts-mcp-server/bin/receipts-mcp-server
```

Сервер использует Streamable HTTP transport и по умолчанию слушает
`http://127.0.0.1:3002/mcp`. Порт можно изменить переменной `MCP_PORT`.

Сервер публикует read-only tools:

- `search-receipts`;
- `get-receipt`.

Клиенты могут выполнить `tools/list`; сервер не подключается к реальным чекам.
