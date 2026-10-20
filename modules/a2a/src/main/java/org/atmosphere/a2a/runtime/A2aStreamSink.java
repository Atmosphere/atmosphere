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
package org.atmosphere.a2a.runtime;

import org.atmosphere.a2a.types.Task;
import org.atmosphere.a2a.types.TaskStatusUpdateEvent;

/**
 * Receives the events of one {@code SendStreamingMessage} (or
 * {@code SubscribeToTask}) stream in the order the A2A specification (v1.0.0)
 * gives them: the {@link Task} first — so the client learns the task id and
 * can cancel or resubscribe — then the answer's text as it is produced, then
 * the task's final status; or an error. {@link #complete()} runs once, last,
 * on every path.
 */
public interface A2aStreamSink {

    /** The task this stream is about; sent before anything else. */
    default void task(Task task) {
    }

    /** The next piece of the answer, as the skill produces it. */
    void token(String text);

    /** The task's status when the stream ends (a terminal state). */
    default void status(TaskStatusUpdateEvent update) {
    }

    /** The stream failed; {@code message} says why. */
    default void error(String message) {
    }

    /** The stream is over. */
    default void complete() {
    }
}
