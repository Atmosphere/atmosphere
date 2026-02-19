# Modernize Java: text blocks

Convert multi-line string concatenation to JDK 15+ text blocks in `{{FILE}}`.

## When to convert

- Static strings built with `\n` concatenation across multiple lines.
- SQL queries, HTML snippets, JSON templates, log message templates.
- Strings where readability improves significantly.

## When NOT to convert

- Dynamic strings with many interpolated variables (`"x=" + x + "\ny=" + y`).
  These are better left as concatenation or converted to `String.formatted()`.
- HTTP protocol strings (`\r\n`) — semantics matter.
- Single-line strings that happen to contain `\n`.
- toString() methods that interpolate object state.

## Text block syntax

```java
// OLD
String sql = "SELECT *\n"
           + "FROM users\n"
           + "WHERE active = true";

// NEW
String sql = """
        SELECT *
        FROM users
        WHERE active = true""";
```

## Rules

- Preserve the original indentation style of the surrounding code.
- Make the edits directly using the edit tool.
- Report each conversion with file, line number, description.
