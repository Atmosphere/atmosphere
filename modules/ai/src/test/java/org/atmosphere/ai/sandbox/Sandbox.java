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
package org.atmosphere.ai.sandbox;

/**
 * Test-scope stand-in for {@code atmosphere-sandbox}'s {@code Sandbox} type.
 * {@code atmosphere-ai} matches the sandbox injectable BY CLASS NAME (see
 * {@code DefaultToolRegistry.SANDBOX_CLASS_NAME}) precisely so it never
 * depends on {@code atmosphere-sandbox}; this stub gives the tests a type
 * with the load-bearing fully-qualified name. Test classpath only — never
 * shipped.
 */
public interface Sandbox {
}
