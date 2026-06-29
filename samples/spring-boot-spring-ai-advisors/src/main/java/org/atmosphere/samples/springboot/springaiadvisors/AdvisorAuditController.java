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
package org.atmosphere.samples.springboot.springaiadvisors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes the advisor side effect observable to a human: it reports how many
 * times each advisor has actually run inside the bound {@code ChatClient}
 * chain. After chatting at {@code /atmosphere/ai-chat}, hit
 * {@code GET /api/advisors/audit-log} to confirm the default advisor fired and,
 * for any {@code audit}-triggered turn, the per-request advisor fired too.
 */
@RestController
@RequestMapping("/api/advisors")
public class AdvisorAuditController {

    private final AdvisorAuditLog auditLog;

    AdvisorAuditController(AdvisorAuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @GetMapping("/audit-log")
    public Map<String, Object> auditLog() {
        var body = new LinkedHashMap<String, Object>();
        body.put("invocations", auditLog.invocations());
        body.put(BoundChatClientConfig.DEFAULT_ADVISOR_NAME,
                auditLog.count(BoundChatClientConfig.DEFAULT_ADVISOR_NAME));
        body.put(BoundChatClientConfig.PER_REQUEST_ADVISOR_NAME,
                auditLog.count(BoundChatClientConfig.PER_REQUEST_ADVISOR_NAME));
        return body;
    }
}
