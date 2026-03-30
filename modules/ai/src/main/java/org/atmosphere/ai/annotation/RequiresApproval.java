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
package org.atmosphere.ai.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link AiTool} method as requiring human approval before execution.
 * When the LLM requests this tool, the pipeline pauses (parks the virtual thread),
 * sends an approval request to the client, and resumes when approved or denied.
 *
 * <pre>{@code
 * @AiTool(name = "delete_account", description = "Permanently delete a user account")
 * @RequiresApproval("This will permanently delete the account. Approve?")
 * public String deleteAccount(@Param("userId") String userId) {
 *     return accountService.delete(userId);
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresApproval {

    /** The approval prompt message shown to the user. */
    String value();

    /** Timeout in seconds before the approval expires. Default 5 minutes. */
    long timeoutSeconds() default 300;
}
