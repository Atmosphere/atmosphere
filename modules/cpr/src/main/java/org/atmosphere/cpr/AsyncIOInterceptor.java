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

import java.io.IOException;

/**
 * A filter-like API that allow an {@link AtmosphereInterceptor} to intercept the response before it gets written back
 * to the client. An AsyncIOInterceptor can only be used with an {@link AtmosphereInterceptorWriter}.
 * <p/>
 * An implementation of this class must make sure the data is written inside the intercept method because otherwise it
 * will be lost.
 *
 * @author Jeanfrancois Arcand
 */
public interface AsyncIOInterceptor {

    void prePayload(AtmosphereResponse response, byte[] data, int offset, int length);

    byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException;

    void postPayload(AtmosphereResponse response, byte[] data, int offset, int length);

    byte[] error(AtmosphereResponse response, int statusCode, String reasonPhrase);

    void redirect(AtmosphereResponse response, String location);
}
