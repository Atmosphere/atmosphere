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

import static org.atmosphere.interceptor.JavaScriptProtocol.tryParseVersion;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

public class JavaScriptProtocolTest {

    static Stream<Arguments> versions() {
        return Stream.of(
                Arguments.of("2.2.1", 221),
                Arguments.of("2.2.1.beta", 221),
                Arguments.of("1.0.10", 1010),
                Arguments.of("3.0.0.RC1", 300),
                Arguments.of("0.0.0.snapshot", 0),
                Arguments.of("1.2.3.snapshot.x", 123),
                Arguments.of("1...", 0),
                Arguments.of("2.", 0),
                Arguments.of("2.2", 0),
                Arguments.of("1.2.a", 0),
                Arguments.of("invalid", 0),
                Arguments.of("", 0)
        );
    }

    @ParameterizedTest
    @MethodSource("versions")
    public void testTryParseVersion(String version, int expected) {
        assertEquals(expected, tryParseVersion(version));
    }
}
