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
package org.atmosphere.agent.command;

import org.atmosphere.agent.annotation.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Scans an {@code @Agent}-annotated class for {@code @Command} methods,
 * validates their signatures, and provides lookup by command prefix.
 * Automatically generates a {@code /help} command listing all registered commands.
 */
public final class CommandRegistry {

    private static final Logger logger = LoggerFactory.getLogger(CommandRegistry.class);

    /**
     * A registered command entry.
     *
     * @param prefix      the command prefix (e.g. "/status")
     * @param description human-readable description
     * @param confirm     confirmation prompt (empty if none)
     * @param method      the method to invoke
     * @param paramType   the parameter type (NONE, STRING, or INCOMING_MESSAGE)
     */
    public record CommandEntry(
            String prefix,
            String description,
            String confirm,
            Method method,
            ParamType paramType
    ) {
    }

    /**
     * Parameter type for a command method.
     */
    public enum ParamType {
        NONE, STRING, INCOMING_MESSAGE
    }

    private static final String INCOMING_MESSAGE_CLASS = "org.atmosphere.channels.IncomingMessage";

    private final Map<String, CommandEntry> commands = new LinkedHashMap<>();

    /**
     * Scans the given class for {@code @Command} methods and registers them.
     *
     * @param clazz the class to scan
     * @throws IllegalArgumentException if a command has an invalid signature or duplicate prefix
     */
    public void scan(Class<?> clazz) {
        for (var method : clazz.getDeclaredMethods()) {
            var annotation = method.getAnnotation(Command.class);
            if (annotation == null) {
                continue;
            }

            var prefix = annotation.value();
            validatePrefix(prefix, method);
            validateReturnType(method);
            var paramType = validateAndResolveParamType(method);

            if (commands.containsKey(prefix)) {
                throw new IllegalArgumentException(
                        "Duplicate @Command prefix '" + prefix + "' in " + clazz.getName()
                                + ": " + commands.get(prefix).method().getName()
                                + " and " + method.getName());
            }

            method.setAccessible(true);
            commands.put(prefix, new CommandEntry(
                    prefix, annotation.description(), annotation.confirm(),
                    method, paramType));
            logger.debug("Registered command: {} -> {}.{}()",
                    prefix, clazz.getSimpleName(), method.getName());
        }
    }

    /**
     * Looks up a command by prefix.
     */
    public Optional<CommandEntry> lookup(String prefix) {
        return Optional.ofNullable(commands.get(prefix));
    }

    /**
     * Returns all registered commands.
     */
    public Collection<CommandEntry> allCommands() {
        return List.copyOf(commands.values());
    }

    /**
     * Returns the number of registered commands (excluding auto-generated /help).
     */
    public int size() {
        return commands.size();
    }

    /**
     * Generates the auto-generated /help response listing all commands.
     */
    public String generateHelp() {
        if (commands.isEmpty()) {
            return "No commands available.";
        }
        var sb = new StringBuilder("Available commands:\n");
        for (var entry : commands.values()) {
            sb.append("  ").append(entry.prefix());
            if (!entry.description().isEmpty()) {
                sb.append(" — ").append(entry.description());
            }
            sb.append("\n");
        }
        sb.append("  /help — Show this help message");
        return sb.toString().trim();
    }

    private void validatePrefix(String prefix, Method method) {
        if (!prefix.startsWith("/")) {
            throw new IllegalArgumentException(
                    "@Command value must start with '/': '" + prefix
                            + "' on method " + method.getDeclaringClass().getName()
                            + "." + method.getName());
        }
    }

    private void validateReturnType(Method method) {
        if (method.getReturnType() != String.class) {
            throw new IllegalArgumentException(
                    "@Command method must return String: "
                            + method.getDeclaringClass().getName() + "." + method.getName()
                            + " returns " + method.getReturnType().getName());
        }
    }

    private ParamType validateAndResolveParamType(Method method) {
        var params = method.getParameterTypes();
        if (params.length == 0) {
            return ParamType.NONE;
        }
        if (params.length == 1) {
            if (params[0] == String.class) {
                return ParamType.STRING;
            }
            if (params[0].getName().equals(INCOMING_MESSAGE_CLASS)) {
                throw new IllegalArgumentException(
                        "@Command method with IncomingMessage parameter is not yet supported: "
                                + method.getDeclaringClass().getName() + "." + method.getName()
                                + ". Use String parameter instead.");
            }
            throw new IllegalArgumentException(
                    "@Command method parameter must be String: "
                            + method.getDeclaringClass().getName() + "." + method.getName()
                            + " has " + params[0].getName());
        }
        throw new IllegalArgumentException(
                "@Command method must have 0 or 1 parameters: "
                        + method.getDeclaringClass().getName() + "." + method.getName()
                        + " has " + params.length);
    }
}
