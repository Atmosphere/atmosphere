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

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class ServletContextFactoryTest {

    @Test
    void getDefaultReturnsSingleton() {
        ServletContextFactory first = ServletContextFactory.getDefault();
        ServletContextFactory second = ServletContextFactory.getDefault();
        assertNotNull(first);
        assertSame(first, second);
    }

    @Test
    void getServletContextReturnsNullByDefault() {
        // Reset by initializing with null
        ServletContextFactory.getDefault().init(null);
        assertNull(ServletContextFactory.getDefault().getServletContext());
    }

    @Test
    void initSetsServletContext() {
        ServletContext ctx = mock(ServletContext.class);
        ServletContextFactory.getDefault().init(ctx);
        assertSame(ctx, ServletContextFactory.getDefault().getServletContext());
        // Cleanup
        ServletContextFactory.getDefault().init(null);
    }
}
