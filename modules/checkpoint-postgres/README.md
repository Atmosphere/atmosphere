# atmosphere-checkpoint-postgres

JDBC-backed `CheckpointStore`. Targets PostgreSQL but is written against
portable SQL, so the same code runs on Postgres and on H2's
PostgreSQL-compatibility mode (which the test suite uses).

## Maven Coordinates

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-checkpoint-postgres</artifactId>
    <version>${atmosphere.version}</version>
</dependency>
```

The module pins **no JDBC driver** — the operator supplies the driver and the
pooling `DataSource`.

## Usage

```java
var store = new PostgresCheckpointStore(dataSource);            // table "checkpoints"
var store = new PostgresCheckpointStore(dataSource, "cp_prod"); // custom table
store.start();                                                  // creates/migrates the schema
```

- **Ownership**: the store never closes the `DataSource` — it did not create it.
  `stop()` releases only what the store registered (its listeners).
- **Bounded**: at most `DEFAULT_MAX_SNAPSHOTS` (10 000) retained snapshots,
  oldest pruned on save — the same policy as `InMemoryCheckpointStore` and
  `SqliteCheckpointStore`.
- **Encryption at rest**: pass an `AesGcmCheckpointCipher` to encrypt the
  `state_json` / `metadata_json` columns; the plaintext default logs a warning
  on `start()`.
- **Portable upsert**: `DELETE`-then-`INSERT` inside one transaction, not
  Postgres-only `ON CONFLICT`.

## Schema versioning

`start()` stamps the schema version through
`org.atmosphere.checkpoint.SchemaMigrations` (reused directly from
`atmosphere-checkpoint`). The version lives in a shared
`atmosphere_schema_version (component, version)` table keyed by the **table
name**, so several stores over different tables in one database version
independently.

- Fresh database → every step runs, final version stamped.
- Unstamped table that already exists → adopted as version 1, later steps run
  (this is how a pre-versioning deployment upgrades in place).
- Already current → no-op.
- Stamped **newer** than this build → `start()` refuses with an
  `IllegalStateException` naming the database, the found version, and the
  supported version, rather than mis-reading a newer schema.

The `checkpoints` schema is at **version 2**: step 1→2 adds the `state_type`
column (`ALTER TABLE … ADD COLUMN IF NOT EXISTS`). To add a migration, append
one idempotent step to the list in `start()` — never edit or reorder an
existing step — and update `PostgresCheckpointStoreSchemaVersionTest`.

See `modules/checkpoint/README.md` for the full convention.

## Requirements

Java 21+, a JSR-221 `DataSource`. Tested against H2 in PostgreSQL mode; a live
Postgres integration test belongs in a Testcontainers module.
