/*
* Copyright 2012 Jeanfrancois Arcand
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

import java.io.Serializable;
import javax.servlet.http.HttpSession;

/**
 * Capable of restoring HTTP session timeout to given value.
 *
 * @since 0.9
 * @author Miro Bezjak
 */
public final class SessionTimeoutRestorer implements Serializable {

    private final int timeout;

    public SessionTimeoutRestorer(int timeout) {
        this.timeout = timeout;
    }

    public void restore(HttpSession session) {
        session.setMaxInactiveInterval(timeout);
    }

}
