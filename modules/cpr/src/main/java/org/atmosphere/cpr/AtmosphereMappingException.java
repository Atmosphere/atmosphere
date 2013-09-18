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
package org.atmosphere.cpr;

/**
 * Thrown when {@link AsynchronousProcessor} is unable to map the request to an {@link AtmosphereHandler}.
 */
public class AtmosphereMappingException extends RuntimeException {

    public AtmosphereMappingException() {
        super();
    }

    public AtmosphereMappingException(java.lang.String s) {
        super(s);
    }

    public AtmosphereMappingException(java.lang.String s, java.lang.Throwable throwable) {
        super(s, throwable);
    }

    public AtmosphereMappingException(java.lang.Throwable throwable) {
        super(throwable);
    }
}
