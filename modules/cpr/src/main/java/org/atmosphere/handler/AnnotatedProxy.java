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
package org.atmosphere.handler;

/**
 * Marker class for an {@link org.atmosphere.cpr.AtmosphereHandler} proxy of a POJO object.
 *
 * @author Jeanfrancois Arcand
 */
public interface AnnotatedProxy {

    /**
     * The the Object the {@link org.atmosphere.cpr.AtmosphereHandler} is proxing.
     * @return
     */
    public Object target();

}
