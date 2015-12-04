/*
 * Copyright 2015 Async-IO.org
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

/**
 * An Atmosphere request representation. An {@link AtmosphereRequest} is a two-way communication channel between the
 * client and the server. If the {@link org.atmosphere.cpr.AtmosphereRequest#isDestroyable()} is set to false, or if its
 * associated {@link AtmosphereResource} has been suspended, this object can be re-used at any moment between requests.
 * You can use its associated {@link AtmosphereResponse} to write bytes at any moment, making this object bi-directional.
 * <br/>
 *
 * @author Jeanfrancois Arcand
 */
// added for 2.4.x compatibility
public class AtmosphereRequestImpl extends AtmosphereRequest {
    private AtmosphereRequestImpl(Builder b) {
        super(b);
    }
}
