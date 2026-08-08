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
package org.atmosphere.ai.batch;

import org.atmosphere.cpr.AtmosphereConfig;

/**
 * Parsed configuration for Atmosphere's durable batch job endpoint —
 * the async submit/poll sibling of the OpenAI-compatible serving surface,
 * following the same conventions ({@code atmosphere.ai.batch.*} init-params,
 * master switch off by default, optional static bearer key).
 *
 * <ul>
 *   <li>{@code atmosphere.ai.batch.enabled} — master switch,
 *       <strong>{@code false} by default</strong> (Correctness Invariant #6:
 *       a new inbound surface never ships on silently).</li>
 *   <li>{@code atmosphere.ai.batch.api-key} — when set, requests must carry
 *       {@code Authorization: Bearer <key>}; a missing or mismatched key is
 *       rejected with a 401 error envelope. When unset, the endpoint itself
 *       performs no authentication (a startup warning is logged) and relies
 *       on framework-level interceptors — the same posture as
 *       {@code atmosphere.ai.openai.api-key}.</li>
 *   <li>{@code atmosphere.ai.batch.db} — path of the SQLite database backing
 *       the job store. When set, jobs and per-item results survive JVM
 *       restart (jobs left in flight by a crash are marked failed on the next
 *       start). When unset, a bounded in-memory store is used and jobs do
 *       <em>not</em> survive restart.</li>
 *   <li>{@code atmosphere.ai.batch.max-open-jobs} — cap on jobs that are
 *       queued or running at once; submissions past it get a 429.</li>
 *   <li>{@code atmosphere.ai.batch.max-items-per-job} — cap on items in one
 *       submission; larger submissions get a 429.</li>
 *   <li>{@code atmosphere.ai.batch.item-concurrency} — cap on batch items
 *       executing concurrently across all jobs (fair virtual-thread gate).</li>
 *   <li>{@code atmosphere.ai.batch.item-timeout-ms} — per-item wall-clock
 *       bound; an item past it is recorded as failed without killing the job.</li>
 *   <li>{@code atmosphere.ai.batch.retained-terminal-jobs} — how many
 *       finished (completed / failed / cancelled) jobs the store keeps;
 *       older terminal jobs and their items are evicted so the store cannot
 *       grow unbounded (Correctness Invariant #3).</li>
 * </ul>
 *
 * @param enabled              whether the endpoint is registered at all
 * @param apiKey               static bearer key, or {@code null} for no
 *                             endpoint-level auth
 * @param dbPath               SQLite database path, or {@code null} for the
 *                             bounded in-memory store
 * @param maxOpenJobs          cap on queued + running jobs
 * @param maxItemsPerJob       cap on items in a single submission
 * @param itemConcurrency      cap on concurrently executing items
 * @param itemTimeoutMs        per-item wall-clock bound in milliseconds
 * @param retainedTerminalJobs terminal jobs kept before eviction
 */
public record BatchServing(
        boolean enabled,
        String apiKey,
        String dbPath,
        int maxOpenJobs,
        int maxItemsPerJob,
        int itemConcurrency,
        long itemTimeoutMs,
        int retainedTerminalJobs) {

    /** Master switch init-param; default {@code false}. */
    public static final String ENABLED_PARAM = "atmosphere.ai.batch.enabled";

    /** Optional static bearer key init-param. */
    public static final String API_KEY_PARAM = "atmosphere.ai.batch.api-key";

    /** Optional SQLite database path init-param; unset means in-memory. */
    public static final String DB_PARAM = "atmosphere.ai.batch.db";

    /** Cap on queued + running jobs. */
    public static final String MAX_OPEN_JOBS_PARAM = "atmosphere.ai.batch.max-open-jobs";

    /** Cap on items in a single submission. */
    public static final String MAX_ITEMS_PER_JOB_PARAM = "atmosphere.ai.batch.max-items-per-job";

    /** Cap on concurrently executing items across all jobs. */
    public static final String ITEM_CONCURRENCY_PARAM = "atmosphere.ai.batch.item-concurrency";

    /** Per-item wall-clock bound in milliseconds. */
    public static final String ITEM_TIMEOUT_MS_PARAM = "atmosphere.ai.batch.item-timeout-ms";

    /** Terminal jobs retained before the oldest are evicted. */
    public static final String RETAINED_TERMINAL_JOBS_PARAM =
            "atmosphere.ai.batch.retained-terminal-jobs";

    /** Path the batch handler is registered at (sub-paths route inside it). */
    public static final String BATCHES_PATH = "/atmosphere/v1/batches";

    /** Default cap on queued + running jobs. */
    public static final int DEFAULT_MAX_OPEN_JOBS = 8;

    /** Default cap on items in a single submission. */
    public static final int DEFAULT_MAX_ITEMS_PER_JOB = 500;

    /** Default cap on concurrently executing items. */
    public static final int DEFAULT_ITEM_CONCURRENCY = 4;

    /** Default per-item wall-clock bound. */
    public static final long DEFAULT_ITEM_TIMEOUT_MS = 120_000L;

    /** Default number of terminal jobs retained. */
    public static final int DEFAULT_RETAINED_TERMINAL_JOBS = 200;

    public BatchServing {
        apiKey = blankToNull(apiKey);
        dbPath = blankToNull(dbPath);
        if (maxOpenJobs <= 0) {
            throw new IllegalArgumentException("maxOpenJobs must be > 0");
        }
        if (maxItemsPerJob <= 0) {
            throw new IllegalArgumentException("maxItemsPerJob must be > 0");
        }
        if (itemConcurrency <= 0) {
            throw new IllegalArgumentException("itemConcurrency must be > 0");
        }
        if (itemTimeoutMs <= 0) {
            throw new IllegalArgumentException("itemTimeoutMs must be > 0");
        }
        if (retainedTerminalJobs <= 0) {
            throw new IllegalArgumentException("retainedTerminalJobs must be > 0");
        }
    }

    /**
     * Parse the batch serving configuration from the framework's init-params.
     */
    public static BatchServing from(AtmosphereConfig config) {
        return new BatchServing(
                config.getInitParameter(ENABLED_PARAM, false),
                config.getInitParameter(API_KEY_PARAM),
                config.getInitParameter(DB_PARAM),
                config.getInitParameter(MAX_OPEN_JOBS_PARAM, DEFAULT_MAX_OPEN_JOBS),
                config.getInitParameter(MAX_ITEMS_PER_JOB_PARAM, DEFAULT_MAX_ITEMS_PER_JOB),
                config.getInitParameter(ITEM_CONCURRENCY_PARAM, DEFAULT_ITEM_CONCURRENCY),
                config.getInitParameter(ITEM_TIMEOUT_MS_PARAM,
                        (int) DEFAULT_ITEM_TIMEOUT_MS),
                config.getInitParameter(RETAINED_TERMINAL_JOBS_PARAM,
                        DEFAULT_RETAINED_TERMINAL_JOBS));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
