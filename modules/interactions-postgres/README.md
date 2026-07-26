# atmosphere-interactions-postgres

JDBC-backed `InteractionStore` — durable interaction headers and steps over any
JSR-221 `DataSource`. Targets PostgreSQL; the SQL is portable, so the test
suite exercises it against H2 in PostgreSQL-compatibility mode.

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-interactions-postgres</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

The module pins no JDBC driver — the operator chooses the driver and pooling.

## Usage

```java
var store = new PostgresInteractionStore(dataSource);
store.start();   // creates/migrates the interactions + interaction_steps schema
```

`stop()` releases nothing: the `DataSource` is caller-owned, and every
operation borrows and returns its own connection via try-with-resources.

See `modules/interactions/README.md` for the `InteractionStore` SPI, the
sync/background model, and the in-memory and SQLite implementations.

## Schema versioning

`start()` stamps the schema version through
`org.atmosphere.interactions.SchemaMigrations` (inherited from the
`atmosphere-interactions` dependency). The version lives in a shared
`atmosphere_schema_version (component, version)` table keyed by the anchor
table `interactions`.

- Fresh database → every step runs, final version stamped.
- Unstamped schema whose tables exist → adopted as version 1, later steps run.
- Already current → no-op.
- Stamped **newer** than this build → `start()` refuses with an
  `IllegalStateException` naming the database, the found version, and the
  supported version (fail closed instead of mis-reading a newer schema).

The `interactions` schema is at **version 1**. To add a migration, append one
idempotent step to the list in `start()` here **and** in
`SqliteInteractionStore.start()` so both backends stay in lockstep — never edit
or reorder an existing step — then update
`PostgresInteractionStoreSchemaVersionTest`.

See `modules/checkpoint/README.md` for the full convention.

## Requirements

Java 21+, a JSR-221 `DataSource`.
