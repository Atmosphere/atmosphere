/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { beforeEach } from 'vitest';

// A transport stores the run id it captures in sessionStorage and a transport
// created later starts with it (see src/utils/run-id-store.ts). jsdom keeps
// sessionStorage for the whole test file, so without this a run id one test
// captured would reach the transports of the tests after it.
beforeEach(() => {
  try {
    globalThis.sessionStorage?.clear();
  } catch {
    // No usable sessionStorage in this environment: nothing to clear.
  }
});
