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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Routes messages to {@link CommandRegistry} commands. Handles the confirmation
 * flow for destructive commands and falls through to the LLM pipeline for
 * non-command messages.
 *
 * <p>Thread-safe: uses per-client locks to prevent race conditions in the
 * confirmation flow (e.g., concurrent "yes" and a new command for the same client).</p>
 */
public final class CommandRouter {

    private static final Logger logger = LoggerFactory.getLogger(CommandRouter.class);
    private static final long CONFIRMATION_TIMEOUT_MS = 60_000;
    private static final long CLIENT_IDLE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    private final CommandRegistry registry;
    private final Object target;
    private final ConcurrentHashMap<String, PendingConfirmation> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> clientLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> clientLastSeen = new ConcurrentHashMap<>();

    record PendingConfirmation(CommandRegistry.CommandEntry command, String args, Instant createdAt) {
    }

    /**
     * @param registry the command registry to route against
     * @param target   the agent instance to invoke commands on
     */
    public CommandRouter(CommandRegistry registry, Object target) {
        this.registry = registry;
        this.target = target;
    }

    /**
     * Routes a message from a client.
     *
     * @param clientId the unique client identifier (must not be null)
     * @param message  the raw message text
     * @return the routing result
     */
    public CommandResult route(String clientId, String message) {
        if (clientId == null) {
            throw new IllegalArgumentException("clientId must not be null");
        }
        if (message == null || message.isBlank()) {
            return new CommandResult.NotACommand();
        }

        // Track last-seen time for idle eviction
        clientLastSeen.put(clientId, System.currentTimeMillis());

        // Periodic cleanup of expired pending confirmations and idle clients
        cleanupExpiredPending();
        cleanupIdleClients();

        // Per-client lock prevents race conditions in confirmation flow.
        var lock = clientLocks.computeIfAbsent(clientId, k -> new ReentrantLock());
        lock.lock();
        try {
            return routeUnderLock(clientId, message.trim());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of pending confirmations (visible for testing).
     */
    int pendingCount() {
        return pending.size();
    }

    private CommandResult routeUnderLock(String clientId, String trimmed) {
        // Check for pending confirmation response
        if (isConfirmation(trimmed)) {
            var pendingCmd = pending.remove(clientId);
            if (pendingCmd != null && !isExpired(pendingCmd)) {
                return executeCommand(pendingCmd.command(), pendingCmd.args());
            }
            // Expired or no pending — fall through to LLM
        }

        // Check for denial of pending confirmation
        if (isDenial(trimmed)) {
            var removed = pending.remove(clientId);
            if (removed != null) {
                return new CommandResult.Executed("Command cancelled.");
            }
        }

        // Not a command prefix
        if (!trimmed.startsWith("/")) {
            return new CommandResult.NotACommand();
        }

        // Parse command prefix and args, stripping @botname suffix (Telegram sends /help@BotName)
        var spaceIndex = trimmed.indexOf(' ');
        var rawPrefix = spaceIndex > 0 ? trimmed.substring(0, spaceIndex) : trimmed;
        var atIndex = rawPrefix.indexOf('@');
        var prefix = atIndex > 0 ? rawPrefix.substring(0, atIndex) : rawPrefix;
        var args = spaceIndex > 0 ? trimmed.substring(spaceIndex + 1).trim() : "";

        // Handle built-in /help
        if ("/help".equals(prefix)) {
            return new CommandResult.Executed(registry.generateHelp());
        }

        var entry = registry.lookup(prefix);
        if (entry.isEmpty()) {
            return new CommandResult.NotACommand();
        }

        var command = entry.get();

        // Confirmation flow
        if (!command.confirm().isEmpty()) {
            pending.put(clientId, new PendingConfirmation(command, args, Instant.now()));
            return new CommandResult.ConfirmationRequired(command.confirm());
        }

        return executeCommand(command, args);
    }

    private CommandResult executeCommand(CommandRegistry.CommandEntry command, String args) {
        try {
            String result = switch (command.paramType()) {
                case NONE -> (String) command.method().invoke(target);
                case STRING -> (String) command.method().invoke(target, args);
                case INCOMING_MESSAGE -> throw new IllegalStateException(
                        "INCOMING_MESSAGE commands should be rejected at registration time");
            };
            logger.debug("Command {} executed: {}", command.prefix(),
                    result != null ? result.substring(0, Math.min(result.length(), 50)) : "null");
            return new CommandResult.Executed(result != null ? result : "");
        } catch (InvocationTargetException e) {
            var cause = e.getCause() != null ? e.getCause() : e;
            logger.error("Command {} failed: {}", command.prefix(), cause.getMessage(), cause);
            return new CommandResult.Executed("Error: " + cause.getMessage());
        } catch (IllegalAccessException e) {
            logger.error("Command {} not accessible: {}", command.prefix(), e.getMessage(), e);
            return new CommandResult.Executed("Error: command not accessible");
        }
    }

    /**
     * Removes expired entries from the pending confirmations map.
     */
    private void cleanupExpiredPending() {
        pending.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    /**
     * Evicts client locks and last-seen entries for clients that have no pending
     * confirmation and haven't been seen in {@value #CLIENT_IDLE_TIMEOUT_MS} ms.
     * Prevents unbounded growth from unique Telegram/Slack sender IDs.
     */
    private void cleanupIdleClients() {
        long now = System.currentTimeMillis();
        clientLastSeen.entrySet().removeIf(entry -> {
            var clientId = entry.getKey();
            var lastSeen = entry.getValue();
            if (now - lastSeen > CLIENT_IDLE_TIMEOUT_MS && !pending.containsKey(clientId)) {
                clientLocks.remove(clientId);
                return true;
            }
            return false;
        });
    }

    private boolean isConfirmation(String message) {
        var lower = message.toLowerCase();
        return "yes".equals(lower) || "y".equals(lower);
    }

    private boolean isDenial(String message) {
        var lower = message.toLowerCase();
        return "no".equals(lower) || "n".equals(lower) || "cancel".equals(lower);
    }

    private boolean isExpired(PendingConfirmation pc) {
        return Instant.now().toEpochMilli() - pc.createdAt().toEpochMilli() > CONFIRMATION_TIMEOUT_MS;
    }
}
