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

import jakarta.servlet.Filter;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FilterConfigImplTest {

    private ServletConfig servletConfig;
    private ServletContext servletContext;
    private FilterConfigImpl filterConfig;

    @BeforeEach
    void setUp() {
        servletConfig = mock(ServletConfig.class);
        servletContext = mock(ServletContext.class);
        when(servletConfig.getServletContext()).thenReturn(servletContext);
        filterConfig = new FilterConfigImpl(servletConfig);
    }

    @Test
    void getInitParameterDelegatesToServletConfig() {
        when(servletConfig.getInitParameter("key")).thenReturn("value");
        assertEquals("value", filterConfig.getInitParameter("key"));
    }

    @Test
    void getInitParameterNamesDelegate() {
        var names = Collections.enumeration(Collections.singletonList("param1"));
        when(servletConfig.getInitParameterNames()).thenReturn(names);
        assertSame(names, filterConfig.getInitParameterNames());
    }

    @Test
    void getServletContextDelegates() {
        assertSame(servletContext, filterConfig.getServletContext());
    }

    @Test
    void filterNameIsInitiallyNull() {
        assertNull(filterConfig.getFilterName());
    }

    @Test
    void setAndGetFilterName() {
        filterConfig.setFilterName("myFilter");
        assertEquals("myFilter", filterConfig.getFilterName());
    }

    @Test
    void filterIsInitiallyNull() {
        assertNull(filterConfig.getFilter());
    }

    @Test
    void setAndGetFilter() {
        Filter filter = mock(Filter.class);
        filterConfig.setFilter(filter);
        assertSame(filter, filterConfig.getFilter());
    }

    @Test
    void recycleCallsDestroyAndNullifiesFilter() {
        Filter filter = mock(Filter.class);
        filterConfig.setFilter(filter);
        filterConfig.recycle();
        verify(filter).destroy();
        assertNull(filterConfig.getFilter());
    }

    @Test
    void recycleWithNullFilterDoesNotThrow() {
        filterConfig.recycle();
        assertNull(filterConfig.getFilter());
    }
}
