# atmosphere-durable-sessions-sqlite

SQLite-backed durability for sessions, conversations, and long-term memory —
single-node persistence that survives a JVM restart with no external service.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-durable-sessions-sqlite</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

## What Ships

| Class | Implements | Default file | Table |
|---|---|---|---|
| `SqliteSessionStore` | `SessionStore` (`atmosphere-durable-sessions`) | `atmosphere-sessions.db` | `durable_sessions` |
| `SqliteConversationPersistence` | `ConversationPersistence` (`atmosphere-ai`) | `atmosphere-conversations.db` | `ai_conversations` |
| `SqliteLongTermMemory` | `LongTermMemory` (`atmosphere-ai`) | `atmosphere-facts.db` | `ai_user_facts` |

```java
var sessions = new SqliteSessionStore(Path.of("/var/lib/app/sessions.db"));
var facts    = new SqliteLongTermMemory(Path.of("/var/lib/app/facts.db"), 100);
```

- Each store opens its connection in **WAL** journal mode and creates parent
  directories as needed.
- `SqliteConversationPersistence` and `SqliteLongTermMemory` also accept an
  existing `Connection` so the trio can share one database file; a store given
  a connection it did not open does not close it (ownership).
- `inMemory()` factories exist for tests.
- `SqliteLongTermMemory` is bounded per user (`maxFacts`, default 100).

## Schema versioning

Every store stamps its schema version when it opens, via the package-local
`SchemaMigrations` (a copy of the canonical
`org.atmosphere.checkpoint.SchemaMigrations` — copied, not depended on, so this
module gains no dependency on `atmosphere-checkpoint`). The version lives in a
shared `atmosphere_schema_version (component, version)` table keyed by the
store's own table, so the three stores version independently even when they
share one file.

- Fresh database → every step runs, final version stamped.
- Unstamped database whose table exists → adopted as version 1, later steps run.
- Already current → no-op.
- Stamped **newer** than this build → the constructor refuses with an
  `IllegalStateException` naming the file, the found version, and the supported
  version, and closes the connection it opened.

All three schemas are at **version 1**. To add a migration, append one
idempotent step to the store's `createTable()` list — never edit or reorder an
existing step — and update `SqliteSchemaVersionTest`.

See `modules/checkpoint/README.md` for the full convention.

## Requirements

Java 21+. The SQLite JDBC driver ships as a compile dependency of this module.
