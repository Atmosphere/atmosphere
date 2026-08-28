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
package org.atmosphere.samples.springboot.lowlevel;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Surfaces the three listener layers side by side so the difference between them is
 * visible rather than described.
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of(
                "resource", Map.of(
                        "suspended", ConnectionHealth.suspended(),
                        "disconnected", ConnectionHealth.disconnected()),
                "transport", Map.of(
                        "timeouts", TransportHealth.timeouts(),
                        "closes", TransportHealth.closes()),
                "framework", Map.of(
                        "ready", FrameworkUptime.startedAt() != null,
                        "uptimeSeconds", FrameworkUptime.uptime().toSeconds()));
    }
}
