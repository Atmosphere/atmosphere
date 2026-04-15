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
package org.atmosphere.util;

import org.atmosphere.cpr.AnnotationProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class VoidAnnotationProcessorTest {

    private VoidAnnotationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new VoidAnnotationProcessor();
    }

    @Test
    void scanFileReturnsSelf() {
        AnnotationProcessor result = processor.scan(new File("/tmp"));
        assertSame(processor, result);
    }

    @Test
    void scanPackageReturnsSelf() {
        AnnotationProcessor result = processor.scan("org.atmosphere");
        assertSame(processor, result);
    }

    @Test
    void scanAllReturnsSelf() {
        AnnotationProcessor result = processor.scanAll();
        assertSame(processor, result);
    }

    @Test
    void destroyIsNoOp() {
        processor.destroy();
        // Should not throw
    }

    @Test
    void configureIsNoOp() {
        processor.configure(mock(AtmosphereConfig.class));
        // Should not throw
    }
}
