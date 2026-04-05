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
package org.atmosphere.checkpoint;

/**
 * Callback invoked by a {@link CheckpointStore} after every lifecycle event.
 * Implementations must be thread-safe and non-blocking — stores may invoke
 * listeners on the calling thread.
 */
@FunctionalInterface
public interface CheckpointListener {

    /**
     * Called after a checkpoint lifecycle event. Throwing from this method
     * must not abort the originating store operation; stores are required
     * to log and swallow exceptions.
     */
    void onEvent(CheckpointEvent event);
}
