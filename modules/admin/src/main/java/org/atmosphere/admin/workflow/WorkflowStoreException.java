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
package org.atmosphere.admin.workflow;

/**
 * Runtime exception thrown by {@link WorkflowStore} implementations when a
 * save / load / delete cannot complete. The admin controller maps it to
 * HTTP 409 (conflict / version mismatch) or 500 (storage failure)
 * depending on the cause.
 */
public class WorkflowStoreException extends RuntimeException {

    public enum Kind {
        VERSION_CONFLICT,
        STORAGE_FAILURE
    }

    private final Kind kind;

    public WorkflowStoreException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public WorkflowStoreException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
