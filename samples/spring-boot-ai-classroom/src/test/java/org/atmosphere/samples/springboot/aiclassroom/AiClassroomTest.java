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

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClassroomTest {

    @Test
    void onReadyBroadcastsPresenceAsRawMessage() throws Exception {
        var endpoint = new AiClassroom();
        setRoom(endpoint, "math");

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
        setRoom(endpoint, "math");

        var event = eventProxy(null);
        assertDoesNotThrow(() -> endpoint.onDisconnect(event));
    }

    @Test
    void onDisconnectLeaveAdjustsCountWhenResourceStillPresent() throws Exception {
        var endpoint = new AiClassroom();
        setRoom(endpoint, "math");

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
        setRoom(endpoint, "math");

        var resource = resourceProxy("student-3", null);
        assertDoesNotThrow(() -> endpoint.onDisconnect(eventProxy(resource)));
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
        return (AtmosphereResource) Proxy.newProxyInstance(
                AtmosphereResource.class.getClassLoader(),
                new Class<?>[] { AtmosphereResource.class },
                (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "uuid" -> uuid;
                        case "getBroadcaster" -> broadcaster;
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

    private static void setRoom(AiClassroom endpoint, String room) throws Exception {
        Field field = AiClassroom.class.getDeclaredField("room");
        field.setAccessible(true);
        field.set(endpoint, room);
    }
}
