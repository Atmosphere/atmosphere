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
package org.atmosphere.samples.springboot.guardedemail;

import org.atmosphere.verifier.PlanAndVerify;
import org.atmosphere.verifier.PlanVerificationException;
import org.atmosphere.verifier.spi.Violation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST surface for the guarded email agent. POST a JSON {@code goal} and
 * the controller routes through {@link PlanAndVerify}; on verification
 * refusal it returns HTTP 403 with structured violation diagnostics.
 *
 * <p>Why 403 and not 400? The plan was syntactically valid — it just
 * tried to do something the policy forbids. 400 would imply user input
 * was malformed; 403 ("forbidden") matches the dataflow refusal more
 * accurately and is what monitoring stacks key off when alerting on
 * attempted policy bypass.</p>
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private final PlanAndVerify planAndVerify;

    public AgentController(PlanAndVerify planAndVerify) {
        this.planAndVerify = planAndVerify;
    }

    public record AgentRequest(String goal) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> handle(@RequestBody AgentRequest request) {
        if (request == null || request.goal() == null || request.goal().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "missing 'goal'"));
        }
        try {
            Map<String, Object> env = planAndVerify.run(request.goal(), Map.of());
            var body = new LinkedHashMap<String, Object>();
            body.put("status", "executed");
            body.put("env", env);
            return ResponseEntity.ok(body);
        } catch (PlanVerificationException ex) {
            // The headline path: a tainted plan was refused before any
            // tool fired. Surface the violation list as structured JSON
            // so audit / SOC pipelines can parse it.
            var body = new LinkedHashMap<String, Object>();
            body.put("status", "refused");
            body.put("reason", "plan failed verifier chain");
            body.put("violations", renderViolations(ex.result().violations()));
            body.put("plan", Map.of(
                    "goal", ex.workflow().goal(),
                    "steps", ex.workflow().steps().size()));
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
    }

    private static List<Map<String, Object>> renderViolations(List<Violation> violations) {
        var out = new java.util.ArrayList<Map<String, Object>>(violations.size());
        for (Violation v : violations) {
            var m = new LinkedHashMap<String, Object>();
            m.put("category", v.category());
            m.put("message", v.message());
            if (v.astPath() != null) {
                m.put("path", v.astPath());
            }
            out.add(m);
        }
        return out;
    }
}
