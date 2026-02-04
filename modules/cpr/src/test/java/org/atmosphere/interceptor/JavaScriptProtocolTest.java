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
package org.atmosphere.interceptor;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.atmosphere.interceptor.JavaScriptProtocol.tryParseVersion;
import static org.testng.Assert.assertEquals;

public class JavaScriptProtocolTest {

    @DataProvider(name = "versions")
    public Object[][] versions() {
        return new Object[][]{
                // Valid input
                {"2.2.1", 221},
                {"2.2.1.beta", 221},
                {"1.0.10", 1010},
                {"3.0.0.RC1", 300},
                {"0.0.0.snapshot", 0},
                {"1.2.3.snapshot.x", 123},

                // Invalid input
                {"1...", 0},
                {"2.", 0},
                {"2.2", 0},
                {"1.2.a", 0},
                {"invalid", 0},
                {"", 0}
        };
    }

    @Test(dataProvider = "versions")
    public void testTryParseVersion(String version, int expected) {
        assertEquals(tryParseVersion(version), expected);
    }
}
