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
package org.atmosphere.samples.springboot.reattach;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Reports how the harness's model call for a prompt ended, so a test asks the
 * server whether a disconnect cancelled the call rather than inferring it from
 * what one client received.
 *
 * <p>{@code GET /harness/model-call?prompt=<prompt>} answers
 * {@code {"prompt":…,"state":"RUNNING"|"COMPLETED"|"CANCELLED","tokens":n,"total":12}},
 * or 404 when no call answered that prompt.</p>
 */
@RestController
@RequestMapping("/harness")
public class ModelCallController {

    private final PacedModelClient model;

    public ModelCallController(PacedModelClient model) {
        this.model = model;
    }

    @GetMapping("/model-call")
    public ResponseEntity<Map<String, Object>> modelCall(@RequestParam("prompt") String prompt) {
        return model.call(prompt)
                .map(call -> ResponseEntity.ok(Map.<String, Object>of(
                        "prompt", call.prompt(),
                        "state", call.state().name(),
                        "tokens", call.tokens(),
                        "total", PacedModelClient.TOKENS_PER_REPLY)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
