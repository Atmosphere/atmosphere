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
package org.atmosphere.ai;

/**
 * A turn that ended because the approval one of its tools asked for was not
 * given before the turn's time limit: an expected end of the turn (nobody
 * answered), not a failure of the framework or of the application.
 *
 * <p>Pass it to {@link StreamingSession#error(Throwable)} to end the turn. The
 * client receives the same {@code error} frame, carrying the exception's
 * message, as for any other failure; the session logs one warning naming the
 * approval and its tool, with no stack trace.</p>
 */
public class ApprovalTimedOutException extends AiException {

    private final String approvalId;
    private final String toolName;

    /**
     * @param message    the message shown to the client
     * @param approvalId the id of the approval that was not given
     * @param toolName   the tool that waited for it
     */
    public ApprovalTimedOutException(String message, String approvalId, String toolName) {
        super(message);
        this.approvalId = approvalId;
        this.toolName = toolName;
    }

    /** @return the id of the approval that was not given */
    public String approvalId() {
        return approvalId;
    }

    /** @return the tool that waited for the approval */
    public String toolName() {
        return toolName;
    }
}
