# atmosphere-spring-boot3-adapter-tests

Tests only; nothing is published.

`atmosphere-spring-ai`, `atmosphere-spring-ai-alibaba` and `atmosphere-embabel` are built
against Spring Boot 4. Each maps the shared `llm.*` settings onto its framework's own
properties before the auto-configurations run:

- On Spring Boot 4, as an `org.springframework.boot.EnvironmentPostProcessor`. Spring Boot
  4.2 loads only that type and its `spring.factories` key.
- On Spring Boot 3, which has neither, from an `ApplicationListener` that does nothing on
  Spring Boot 4.

This module puts the three adapter jars and the Spring Boot 3 starter on a Spring Boot 3.5
classpath (the line `atmosphere-spring-boot3-starter` and the samples' `spring-boot3`
profile target). It then checks that the listeners run, after the starter has mapped
`atmosphere.ai.*` onto `llm.*`.

```bash
./mvnw test -pl modules/spring-boot3-adapter-tests
```

The Spring Boot 4 side is tested in each adapter module (`*BootTest`). The `CI: Spring Boot
next` workflow runs those tests on the next Spring Boot line too.
