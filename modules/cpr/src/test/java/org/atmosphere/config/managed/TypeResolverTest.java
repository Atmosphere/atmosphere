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
package org.atmosphere.config.managed;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypeResolverTest {

    @BeforeEach
    void setUp() {
        TypeResolver.enableCache();
    }

    @AfterEach
    void tearDown() {
        TypeResolver.disableCache();
    }

    interface StringProcessor extends Comparable<String> {
        @Override
        int compareTo(String o);
    }

    static class StringList extends java.util.ArrayList<String> {
    }

    static class IntegerComparable implements Comparable<Integer> {
        @Override
        public int compareTo(Integer o) {
            return 0;
        }
    }

    static class MultiLevel extends StringList {
    }

    @Test
    void resolveArgumentFromDirectImplementation() {
        var result = TypeResolver.resolveArgument(IntegerComparable.class, Comparable.class);
        assertEquals(Integer.class, result);
    }

    @Test
    void resolveArgumentFromSubclass() {
        var result = TypeResolver.resolveArgument(StringList.class, List.class);
        assertEquals(String.class, result);
    }

    @Test
    void resolveArgumentFromInterface() {
        var result = TypeResolver.resolveArgument(StringProcessor.class, Comparable.class);
        assertEquals(String.class, result);
    }

    @Test
    void resolveArgumentMultiLevelInheritance() {
        var result = TypeResolver.resolveArgument(MultiLevel.class, List.class);
        assertEquals(String.class, result);
    }

    @Test
    void resolveArgumentsReturnsArray() {
        var results = TypeResolver.resolveArguments(StringList.class, List.class);
        assertNotNull(results);
        assertEquals(1, results.length);
        assertEquals(String.class, results[0]);
    }

    @Test
    void resolveArgumentsReturnsNullForUnresolvable() {
        var results = TypeResolver.resolveArguments(Object.class, Comparable.class);
        assertNull(results);
    }

    @Test
    void resolveClassFallsBackToUnknownForRawType() {
        // Unresolvable generic returns the raw class
        var result = TypeResolver.resolveClass(Object.class, Serializable.class);
        assertEquals(Object.class, result);
    }

    @Test
    void resolveGenericTypeFindsParameterizedType() {
        // String implements Comparable<String>, so resolving against Comparable returns the parameterized type
        var result = TypeResolver.resolveGenericType(String.class, Comparable.class);
        assertNotNull(result);
    }

    @Test
    void cacheCanBeDisabledAndReEnabled() {
        TypeResolver.disableCache();
        var result = TypeResolver.resolveArgument(IntegerComparable.class, Comparable.class);
        assertEquals(Integer.class, result);

        TypeResolver.enableCache();
        result = TypeResolver.resolveArgument(IntegerComparable.class, Comparable.class);
        assertEquals(Integer.class, result);
    }

    @Test
    void resolveArgumentThrowsForMultipleTypeParams() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeResolver.resolveArgument(java.util.HashMap.class, java.util.Map.class));
    }
}
