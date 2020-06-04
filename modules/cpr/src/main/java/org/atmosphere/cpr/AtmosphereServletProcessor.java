/*
 * Copyright 2008-2020 Async-IO.org
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

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

/**
 * Simple interface that can be used to wrap a {@link Servlet} from an {@link AtmosphereHandler}.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereServletProcessor extends AtmosphereHandler {

    /**
     * Initialize the {@link AtmosphereServletProcessor} using the {@link ServletConfig}.
     * @param config the {@link javax.servlet.ServletConfig}
     */
    void init(AtmosphereConfig config) throws ServletException;
}
