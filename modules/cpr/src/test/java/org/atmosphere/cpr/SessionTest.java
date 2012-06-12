/*
 * Copyright 2012 Jean-Francois Arcand
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
package org.atmosphere.cpr;

import org.atmosphere.util.FakeHttpSession;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class SessionTest {

    @Test
    public void basicSessionTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        AtmosphereRequest request = new AtmosphereRequest.Builder().build();

        assertNull(request.getSession(false));
        assertNotNull(request.getSession());
        assertNotNull(request.getSession(true));
        assertNotNull(request.getSession());

        request = new AtmosphereRequest.Builder().session(new FakeHttpSession("-1", null, System.currentTimeMillis(), -1)).build();
        assertNotNull(request.getSession());
        assertNotNull(request.getSession(true));
    }
}
