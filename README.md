# receipts-mcp-server

Локальный MCP-сервер синтетических чеков для интеграции со
Smart Expense Agent.

## Сборка

```bash
./gradlew installDist
```

Запускаемый файл после сборки:

```text
build/install/receipts-mcp-server/bin/receipts-mcp-server
```

Сервер использует `stdio` transport. В stdout помещаются только сообщения MCP; диагностические сообщения должны идти в stderr.

Сервер публикует только синтетические read-only tools:

- `search-receipts`;
- `get-receipt`.

Клиенты могут выполнить `tools/list`; сервер не подключается к реальным чекам.
