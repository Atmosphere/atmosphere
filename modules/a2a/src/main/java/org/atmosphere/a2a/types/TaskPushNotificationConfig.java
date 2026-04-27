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
package org.atmosphere.a2a.types;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Push-notification webhook registered against an A2A task. v1.0.0 unified
 * the pre-1.0 nested {@code TaskPushNotificationConfig} +
 * {@code PushNotificationConfig} pair into this single shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskPushNotificationConfig(
    String tenant,
    String id,
    String taskId,
    String url,
    String token,
    AuthenticationInfo authentication
) {
    public TaskPushNotificationConfig(String taskId, String url) {
        this(null, null, taskId, url, null, null);
    }
}
