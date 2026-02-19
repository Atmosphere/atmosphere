# Write unit tests

Write unit tests for `{{FILE}}` using `{{FRAMEWORK}}` (TestNG or JUnit 5).

## Guidelines

- Test public API surface — constructors, public methods, edge cases.
- Test error paths — what happens on null input, invalid state, exceptions.
- Use mocking (`Mockito`) only for external dependencies, not for the class under test.
- Each test method should test one thing. Name: `testMethodName_scenario`.
- Use `@BeforeMethod` / `@BeforeEach` for shared setup.
- Assert expected values, not just "no exception thrown".

## Structure

```java
public class {{CLASS}}Test {

    @BeforeMethod  // or @BeforeEach for JUnit 5
    public void setUp() { ... }

    @Test
    public void testBasicOperation() { ... }

    @Test
    public void testEdgeCase() { ... }

    @Test(expectedExceptions = IllegalArgumentException.class)  // TestNG
    // or @Test with assertThrows for JUnit 5
    public void testInvalidInput() { ... }
}
```

## File header

All Java files must start with the Apache 2.0 copyright header (see AGENTS.md).

## Rules

- Create the test file at the conventional path
  (`src/test/java/...` mirroring the main source path).
- Make the edits directly — create the file, don't just suggest it.
- Run `./mvnw test -pl {{MODULE}} -Dtest={{CLASS}}Test` to verify.
