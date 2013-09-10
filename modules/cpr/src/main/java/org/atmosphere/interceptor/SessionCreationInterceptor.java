/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * An interceptor that create an {@link javax.servlet.http.HttpSession} on the fist received request so transport like long-polling
 * can share the session with request coming after the suspend operation. Without this and because
 * with long-polling cookies aren't read by the browser until the response is resumed, the session id will not
 * be the same so session couldn't be used.
 *
 * @author Jeanfrancois Arcand
 */
public class SessionCreationInterceptor extends AtmosphereInterceptorAdapter {

    // This can cause memory leak.
    private ConcurrentLinkedQueue<String> ids = new ConcurrentLinkedQueue<String>();

    @Override
    public Action inspect(AtmosphereResource r) {
        if (r.session(false) == null
                && !ids.remove(r.uuid())
                && r.getRequest().getMethod().equalsIgnoreCase("GET")) {
            r.session(true);
            ids.offer(r.uuid());
            return Action.CANCELLED;
        }
        return Action.CONTINUE;
    }

}

