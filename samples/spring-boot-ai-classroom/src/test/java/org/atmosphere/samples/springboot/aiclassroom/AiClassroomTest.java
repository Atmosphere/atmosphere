/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.samples.springboot.aiclassroom;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClassroomTest {

    @Test
    void onReadyBroadcastsPresenceAsRawMessage() throws Exception {
        var endpoint = new AiClassroom();

        var captured = new AtomicReference<Object>();
        var members = new LinkedHashSet<AtmosphereResource>();
        var broadcaster = broadcasterProxy("/ai/math", members, captured);
        var resource = resourceProxy("student-1", broadcaster);
        members.add(resource);

        endpoint.onReady(resource);

        var raw = assertInstanceOf(RawMessage.class, captured.get());
        var payload = assertInstanceOf(String.class, raw.message());
        assertTrue(payload.contains("\"type\":\"presence\""));
        assertTrue(payload.contains("\"action\":\"join\""));
    }

    @Test
    void onDisconnectWithNullResourceIsIgnored() throws Exception {
        var endpoint = new AiClassroom();

        var event = eventProxy(null);
        assertDoesNotThrow(() -> endpoint.onDisconnect(event));
    }

    @Test
    void onDisconnectLeaveAdjustsCountWhenResourceStillPresent() throws Exception {
        var endpoint = new AiClassroom();

        var captured = new AtomicReference<Object>();
        var members = new LinkedHashSet<AtmosphereResource>();
        var broadcaster = broadcasterProxy("/ai/math", members, captured);
        var resource = resourceProxy("student-2", broadcaster);
        members.add(resource);

        endpoint.onDisconnect(eventProxy(resource));

        var raw = assertInstanceOf(RawMessage.class, captured.get());
        var payload = assertInstanceOf(String.class, raw.message());
        assertTrue(payload.contains("\"action\":\"leave\""));
        assertTrue(payload.contains("\"count\":0"));
    }

    @Test
    void onDisconnectSkipsBroadcastWhenBroadcasterIsNull() throws Exception {
        var endpoint = new AiClassroom();

        var resource = resourceProxy("student-3", null);
        assertDoesNotThrow(() -> endpoint.onDisconnect(eventProxy(resource)));
    }

    /**
     * One {@code AiClassroom} instance serves every room. With the room in an
     * {@code @PathParam} field, the last student to join decided which room
     * every later callback named: a math student's prompt was logged as the
     * code room's. Each callback must name the room of its own resource.
     */
    @Test
    void promptIsAttributedToTheSendersRoomNotTheLastJoinedOne() {
        var endpoint = new AiClassroom();
        var members = new LinkedHashSet<AtmosphereResource>();
        var math = resourceProxy("math-student", broadcasterProxy("/atmosphere/classroom/math",
                members, new AtomicReference<>()), "math");
        var code = resourceProxy("code-student", broadcasterProxy("/atmosphere/classroom/code",
                members, new AtomicReference<>()), "code");

        var logger = (Logger) LoggerFactory.getLogger(AiClassroom.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            endpoint.onReady(math);
            endpoint.onReady(code);
            endpoint.onPrompt("What is 7 times 8?", sessionProxy(), math);
        } finally {
            logger.detachAppender(appender);
        }

        var prompt = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Classroom prompt"))
                .findFirst()
                .orElseThrow();
        assertEquals("Classroom prompt in room 'math': What is 7 times 8?", prompt);
    }

    @Test
    void endpointKeepsNoPathParamStateOnTheSharedInstance() {
        assertFalse(Arrays.stream(AiClassroom.class.getDeclaredFields())
                        .anyMatch(f -> f.isAnnotationPresent(PathParam.class)),
                "AiClassroom is a single instance for every room; a @PathParam field "
                        + "holds whichever room the last student joined");
    }

    private static StreamingSession sessionProxy() {
        return (StreamingSession) Proxy.newProxyInstance(
                StreamingSession.class.getClassLoader(),
                new Class<?>[] { StreamingSession.class },
                (proxy, method, args) -> "toString".equals(method.getName())
                        ? "StreamingSessionProxy" : defaultValue(method.getReturnType()));
    }

    private static AtmosphereRequest requestProxy(String room) {
        return (AtmosphereRequest) Proxy.newProxyInstance(
                AtmosphereRequest.class.getClassLoader(),
                new Class<?>[] { AtmosphereRequest.class },
                (proxy, method, args) -> {
                    if ("getAttribute".equals(method.getName())) {
                        return (AiEndpointHandler.PATH_PARAM_ATTRIBUTE_PREFIX + "room").equals(args[0])
                                ? room : null;
                    }
                    if ("toString".equals(method.getName())) {
                        return "AtmosphereRequestProxy(" + room + ")";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Broadcaster broadcasterProxy(String id,
                                                Set<AtmosphereResource> members,
                                                AtomicReference<Object> captured) {
        return (Broadcaster) Proxy.newProxyInstance(
                Broadcaster.class.getClassLoader(),
                new Class<?>[] { Broadcaster.class },
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getID" -> id;
                        case "getAtmosphereResources" -> members;
                        case "broadcast" -> {
                            captured.set(args[0]);
                            yield null;
                        }
                        case "toString" -> "BroadcasterProxy(" + id + ")";
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static AtmosphereResource resourceProxy(String uuid, Broadcaster broadcaster) {
        return resourceProxy(uuid, broadcaster, "math");
    }

    private static AtmosphereResource resourceProxy(String uuid, Broadcaster broadcaster, String room) {
        var request = requestProxy(room);
        return (AtmosphereResource) Proxy.newProxyInstance(
                AtmosphereResource.class.getClassLoader(),
                new Class<?>[] { AtmosphereResource.class },
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "uuid" -> uuid;
                        case "getBroadcaster" -> broadcaster;
                        case "getRequest" -> request;
                        case "toString" -> "AtmosphereResourceProxy(" + uuid + ")";
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static AtmosphereResourceEvent eventProxy(AtmosphereResource resource) {
        return (AtmosphereResourceEvent) Proxy.newProxyInstance(
                AtmosphereResourceEvent.class.getClassLoader(),
                new Class<?>[] { AtmosphereResourceEvent.class },
                (proxy, method, args) -> {
                    if ("getResource".equals(method.getName())) {
                        return resource;
                    }
                    if ("toString".equals(method.getName())) {
                        return "AtmosphereResourceEventProxy";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
