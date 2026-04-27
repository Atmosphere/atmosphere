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
 * Response shape for {@code SendMessage}. Models the proto {@code oneof
 * payload {Task task; Message message}} — exactly one of the two fields is
 * populated.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SendMessageResponse(Task task, Message message) {
    public static SendMessageResponse of(Task task) {
        return new SendMessageResponse(task, null);
    }

    public static SendMessageResponse of(Message message) {
        return new SendMessageResponse(null, message);
    }
}
