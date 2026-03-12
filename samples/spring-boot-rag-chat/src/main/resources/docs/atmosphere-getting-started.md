# Getting Started with Atmosphere

## Maven Dependencies

### Spring Boot

Add the Atmosphere Spring Boot Starter to your `pom.xml`:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-spring-boot-starter</artifactId>
    <version>4.0.14-SNAPSHOT</version>
</dependency>
```

For AI features, also add:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-ai</artifactId>
    <version>4.0.14-SNAPSHOT</version>
</dependency>
```

### Quarkus

Add the Atmosphere Quarkus Extension:

```xml
<dependency>
    <groupId>org.atmosphere</groupId>
    <artifactId>atmosphere-quarkus-extension</artifactId>
    <version>4.0.14-SNAPSHOT</version>
</dependency>
```

## Configuration

In Spring Boot, configure the package scanning in `application.yml`:

```yaml
atmosphere:
  packages: com.example.myapp
```

## Creating an Endpoint

### Simple Chat

```java
@ManagedService(path = "/chat")
public class ChatEndpoint {
    @Ready
    public void onReady(AtmosphereResource r) {
        r.getBroadcaster().broadcast("User joined");
    }

    @org.atmosphere.config.service.Message
    public String onMessage(String message) {
        return message; // broadcast to all
    }
}
```

### AI Chat

```java
@AiEndpoint(path = "/ai-chat",
        systemPromptResource = "prompts/system-prompt.md")
public class AiChat {
    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        session.stream(message);
    }
}
```

## Client-Side (atmosphere.js)

```javascript
import { Atmosphere } from '@anthropic/atmosphere.js';

const request = Atmosphere.subscribe({
    url: '/atmosphere/chat',
    transport: 'websocket',
    onMessage: (response) => {
        console.log('Received:', response.responseBody);
    }
});

request.push('Hello, World!');
```

## Running Samples

All samples can be run with Maven:

```bash
cd samples/spring-boot-ai-chat
../../mvnw spring-boot:run
```

Then open http://localhost:8080 in your browser.
