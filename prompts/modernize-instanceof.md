# Modernize Java: pattern matching `instanceof`

Convert old-style `instanceof` + cast patterns to JDK 16+ pattern matching in `{{FILE}}`.

## Pattern to find

```java
// OLD
if (x instanceof Foo) {
    Foo f = (Foo) x;
    f.doSomething();
}

// NEW
if (x instanceof Foo f) {
    f.doSomething();
}
```

Also convert inline casts:

```java
// OLD
if (x instanceof Foo) {
    ((Foo) x).doSomething();
}

// NEW
if (x instanceof Foo f) {
    f.doSomething();
}
```

## Rules

- Only convert when the cast target matches the instanceof type exactly.
- Keep the original variable name if one existed; otherwise pick a short
  idiomatic name (`s` for String, `b` for Boolean, `re` for RuntimeException, etc.).
- If the instanceof is combined with `null` check (`o == null || o instanceof String`),
  **skip it** — pattern variables don't bind on null.
- Do not change semantics. If the cast is in a different scope or branch, leave it.
- Make the edits directly using the edit tool.
- Report each conversion with file, line number, before → after.
