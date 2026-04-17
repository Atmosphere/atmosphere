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
package org.atmosphere.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlAuthorizerTest {

    @Test
    void allowAllAuthorizesEveryCall() {
        var auth = ControlAuthorizer.ALLOW_ALL;
        assertTrue(auth.authorize("broadcast", "t", "alice"));
        assertTrue(auth.authorize("broadcast", "t", null));
    }

    @Test
    void denyAllRejectsEveryCall() {
        var auth = ControlAuthorizer.DENY_ALL;
        assertFalse(auth.authorize("broadcast", "t", "alice"));
        assertFalse(auth.authorize("broadcast", "t", null));
    }

    @Test
    void requirePrincipalAllowsAuthenticatedOnly() {
        var auth = ControlAuthorizer.REQUIRE_PRINCIPAL;
        assertTrue(auth.authorize("broadcast", "t", "alice"));
        assertFalse(auth.authorize("broadcast", "t", null),
                "anonymous principal must be rejected");
        assertFalse(auth.authorize("broadcast", "t", ""),
                "blank principal counts as anonymous");
        assertFalse(auth.authorize("broadcast", "t", "   "),
                "whitespace-only principal counts as anonymous");
    }
}
