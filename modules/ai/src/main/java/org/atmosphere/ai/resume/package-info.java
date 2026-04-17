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
/**
 * Mid-stream reconnect primitives. A run registers itself in
 * {@link org.atmosphere.ai.resume.RunRegistry} on start; the
 * {@link org.atmosphere.ai.resume.RunEventReplayBuffer} captures events
 * while the run is in flight; a client reconnecting with the same
 * {@code runId} gets replayed the events it missed and reattaches to the
 * live {@link org.atmosphere.ai.ExecutionHandle}.
 */
package org.atmosphere.ai.resume;
