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
package org.atmosphere.handler;

import org.junit.jupiter.api.Test;

import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link ReflectorServletProcessor} property accessors
 * and filter registration.
 */
class ReflectorServletProcessorTest {

    @Test
    void defaultConstructorShouldHaveNullServlet() {
        ReflectorServletProcessor processor = new ReflectorServletProcessor();
        assertNull(processor.getServlet());
    }

    @Test
    void constructorWithServletShouldStoreServlet() {
        Servlet servlet = mock(Servlet.class);
        ReflectorServletProcessor processor = new ReflectorServletProcessor(servlet);
        assertSame(servlet, processor.getServlet());
    }

    @Test
    void getServletClassNameShouldReturnNullByDefault() {
        ReflectorServletProcessor processor = new ReflectorServletProcessor();
        assertNull(processor.getServletClassName());
    }

    @Test
    void setServletClassNameShouldStoreClassName() {
        ReflectorServletProcessor processor = new ReflectorServletProcessor();
        processor.setServletClassName("com.example.MyServlet");
        assertEquals("com.example.MyServlet", processor.getServletClassName());
    }

    @Test
    void setServletShouldReplaceServlet() {
        ReflectorServletProcessor processor = new ReflectorServletProcessor();
        Servlet servlet = mock(Servlet.class);
        processor.setServlet(servlet);
        assertSame(servlet, processor.getServlet());
    }

    @Test
    void addFilterShouldAcceptFilterInstance() {
        ReflectorServletProcessor processor = new ReflectorServletProcessor();
        Filter filter = mock(Filter.class);
        // Should not throw
        processor.addFilter(filter);
    }

    @Test
    void setFilterClassNameShouldAcceptNullWithoutError() {
        ReflectorServletProcessor processor = new ReflectorServletProcessor();
        // Null is guarded and should not throw
        processor.setFilterClassName(null);
    }

    @Test
    void setFilterClassNameShouldAcceptClassName() {
        ReflectorServletProcessor processor = new ReflectorServletProcessor();
        processor.setFilterClassName("com.example.MyFilter");
        // No exception expected; filter class is stored for later initialization
    }

    @Test
    void addFilterClassNameShouldAcceptNullsWithoutError() {
        ReflectorServletProcessor processor = new ReflectorServletProcessor();
        // Both null guards should prevent any error
        processor.addFilterClassName(null, null);
    }

    @Test
    void addFilterClassNameShouldStoreClassAndName() {
        ReflectorServletProcessor processor = new ReflectorServletProcessor();
        processor.addFilterClassName("com.example.MyFilter", "myFilter");
        // No exception expected; stored for later initialization
    }

    @Test
    void toStringShouldReturnSimpleClassName() {
        ReflectorServletProcessor processor = new ReflectorServletProcessor();
        assertEquals("ReflectorServletProcessor", processor.toString());
    }
}
