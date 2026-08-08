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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wire-format codec for Atmosphere's batch endpoint. Parses and validates the
 * submission JSON at the boundary (malformed input raises a 4xx
 * {@link BatchError}, never a 500 — Correctness Invariant #4) and builds the
 * outbound job / results / error envelopes.
 *
 * <p><strong>This is Atmosphere's own wire shape, not the OpenAI Batch
 * API.</strong> OpenAI batches upload a JSONL file and reference it by file
 * id; this endpoint takes the requests inline as a JSON array and returns
 * results inline — deliberately simpler, and honestly named as such. Only the
 * error envelope shape ({@code {"error":{...}}}) is shared with the
 * OpenAI-compatible serving surface so clients handle failures uniformly.</p>
 *
 * <p>Submission shape:</p>
 * <pre>{@code
 * {
 *   "agent": "<registered agent / endpoint name>",
 *   "submitter": "optional label",
 *   "items": [
 *     {"custom_id": "optional, defaults to the index", "input": "user message"},
 *     ...
 *   ]
 * }
 * }</pre>
 */
final class BatchWire {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BatchWire() {
    }

    /**
     * A validated inbound batch submission.
     *
     * @param agent     the registered serving name to dispatch through
     * @param submitter caller-supplied submitter label (may be empty)
     * @param items     the validated item requests, in submission order
     */
    record InboundSubmission(String agent, String submitter,
                             List<BatchExecutor.ItemRequest> items) {
    }

    /**
     * Parse and validate a batch submission body.
     *
     * @throws BatchError 400 for malformed input, 429 when the item count
     *                    exceeds {@code maxItems} (Invariant #3)
     */
    static InboundSubmission parse(String body, int maxItems) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JacksonException e) {
            throw BatchError.invalidRequest("Invalid JSON payload.");
        }
        if (root == null || !root.isObject()) {
            throw BatchError.invalidRequest("Request body must be a JSON object.");
        }
        var agentNode = root.get("agent");
        if (agentNode == null || !agentNode.isString() || agentNode.stringValue().isBlank()) {
            throw BatchError.invalidRequest("'agent' is required and must be a non-empty string.",
                    "agent");
        }
        var submitter = "";
        if (root.has("submitter") && !root.get("submitter").isNull()) {
            if (!root.get("submitter").isString()) {
                throw BatchError.invalidRequest("'submitter' must be a string.", "submitter");
            }
            submitter = root.get("submitter").stringValue();
        }
        var itemsNode = root.get("items");
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            throw BatchError.invalidRequest("'items' must be a non-empty array.", "items");
        }
        if (itemsNode.size() > maxItems) {
            throw BatchError.overCapacity("batch has " + itemsNode.size()
                    + " items, exceeding the per-job limit of " + maxItems + ".");
        }
        var items = new ArrayList<BatchExecutor.ItemRequest>(itemsNode.size());
        var customIds = new HashSet<String>(itemsNode.size());
        var index = 0;
        for (var itemNode : itemsNode) {
            if (itemNode == null || !itemNode.isObject()) {
                throw BatchError.invalidRequest("Each item must be a JSON object.", "items");
            }
            var inputNode = itemNode.get("input");
            if (inputNode == null || !inputNode.isString()
                    || inputNode.stringValue().isBlank()) {
                throw BatchError.invalidRequest("Item " + index
                        + " requires a non-empty string 'input'.", "items");
            }
            var customId = Integer.toString(index);
            if (itemNode.has("custom_id") && !itemNode.get("custom_id").isNull()) {
                if (!itemNode.get("custom_id").isString()
                        || itemNode.get("custom_id").stringValue().isBlank()) {
                    throw BatchError.invalidRequest("Item " + index
                            + " 'custom_id' must be a non-empty string.", "items");
                }
                customId = itemNode.get("custom_id").stringValue();
            }
            if (!customIds.add(customId)) {
                throw BatchError.invalidRequest(
                        "Duplicate custom_id '" + customId + "'.", "items");
            }
            items.add(new BatchExecutor.ItemRequest(customId, inputNode.stringValue()));
            index++;
        }
        return new InboundSubmission(agentNode.stringValue().strip(), submitter,
                List.copyOf(items));
    }

    /** Build the {@code "batch"} envelope for one job. */
    static String jobJson(BatchJob job) {
        return MAPPER.writeValueAsString(jobMap(job));
    }

    /** Build the job-listing envelope. */
    static String jobsJson(List<BatchJob> jobs) {
        var data = new ArrayList<Map<String, Object>>(jobs.size());
        for (var job : jobs) {
            data.add(jobMap(job));
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("object", "list");
        body.put("data", data);
        return MAPPER.writeValueAsString(body);
    }

    /** Build the per-item results envelope (partial while the job runs). */
    static String resultsJson(String jobId, List<BatchItem> items) {
        var data = new ArrayList<Map<String, Object>>(items.size());
        for (var item : items) {
            var row = new LinkedHashMap<String, Object>();
            row.put("index", item.index());
            row.put("custom_id", item.customId());
            row.put("status", item.status().wire());
            row.put("output", item.status() == BatchItem.Status.SUCCEEDED ? item.output() : null);
            row.put("error", item.error().isEmpty() ? null : item.error());
            data.add(row);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("object", "list");
        body.put("id", jobId);
        body.put("data", data);
        return MAPPER.writeValueAsString(body);
    }

    /** Build the error envelope for the given boundary error. */
    static String errorJson(BatchError error) {
        var inner = new LinkedHashMap<String, Object>();
        inner.put("message", error.getMessage());
        inner.put("type", error.type());
        inner.put("param", error.param());
        inner.put("code", error.code());
        return MAPPER.writeValueAsString(Map.of("error", inner));
    }

    private static Map<String, Object> jobMap(BatchJob job) {
        var counts = new LinkedHashMap<String, Object>();
        counts.put("total", job.totalItems());
        counts.put("pending", job.pendingItems());
        counts.put("succeeded", job.succeededItems());
        counts.put("failed", job.failedItems());
        counts.put("cancelled", job.cancelledItems());
        var body = new LinkedHashMap<String, Object>();
        body.put("id", job.id());
        body.put("object", "batch");
        body.put("agent", job.agent());
        body.put("status", job.status().wire());
        body.put("created_at", job.createdAt().getEpochSecond());
        body.put("updated_at", job.updatedAt().getEpochSecond());
        body.put("counts", counts);
        body.put("error", job.error().isEmpty() ? null : job.error());
        return body;
    }
}
