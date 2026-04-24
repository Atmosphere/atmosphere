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
package org.atmosphere.ai.governance;

/**
 * Persistent destination for {@link AuditEntry} records written by the
 * {@link GovernanceDecisionLog}. The framework always records into an
 * in-process ring buffer for short-lived triage; operators register one
 * or more {@code AuditSink}s to persist decisions to external stores
 * (Kafka, Postgres, S3, SIEM pipelines) for long-term retention and
 * compliance queries.
 *
 * <p>Implementations are invoked on the caller's thread from
 * {@link GovernanceDecisionLog#record(AuditEntry)}. Sinks that can block
 * (network IO, JDBC) <b>must</b> manage their own batching or async dispatch
 * — a slow sink directly back-pressures the admission path. The
 * {@code AsyncAuditSink} wrapper in this package makes "drop on queue full"
 * one line of configuration.</p>
 *
 * <p>Sink failures are isolated: {@link GovernanceDecisionLog} catches any
 * exception from {@link #write} and logs without propagating, so one bad
 * sink does not take the pipeline down. Sinks that want to fail-closed
 * must wrap their {@code write} call with a guardrail that throws a
 * {@link SecurityException} on write failure — not in scope for this SPI.</p>
 */
public interface AuditSink {

    /**
     * Persist one entry. Called synchronously on the admission thread —
     * implementations that touch the network must be async or bounded.
     *
     * @param entry a framework-built {@link AuditEntry}; never {@code null}
     */
    void write(AuditEntry entry);

    /**
     * Release any resources held by this sink (connections, background
     * threads, flush buffers). Called when the framework shuts down or
     * when an operator explicitly uninstalls this sink. Default is a
     * no-op for stateless sinks (logging, stdout).
     */
    default void close() { }

    /**
     * Short identifier used in log messages — useful so operators can tell
     * which of several registered sinks tripped a warning. Defaults to the
     * simple class name.
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
