# Modernize Java: `var` declarations

Replace obvious type declarations with `var` (JDK 11+) in `{{FILE}}`.

## Rules — REPLACE when type is obvious from RHS

- `Type x = new Type(...)` → `var x = new Type(...)`
- `StringBuilder sb = new StringBuilder()` → `var sb = new StringBuilder()`
- `Map<K,V> x = new HashMap<>()` → `var x = new HashMap<K,V>()` (move diamond type args)
- `List<T> x = new ArrayList<>()` → `var x = new ArrayList<T>()`
- `Iterator<T> x = col.iterator()` → `var x = col.iterator()`
- `Class<?> c = Class.forName(...)` → `var c = Class.forName(...)`

## Rules — DO NOT replace

- Fields (var not allowed on fields)
- Method parameters (var not allowed)
- Interface-typed variables where the interface matters
  (e.g. `List<X> x = getItems()` — keep the interface type)
- Lambdas, generic method returns where type is ambiguous
- Casts

## Scope

- Focus on constructor calls (`new XXX()`), `StringBuilder`, collection constructors,
  `Class.forName()`, iterator patterns.
- Make the edits directly using the edit tool.
- Don't change anything else — only type declarations to var.
- Report which lines changed and how many.
