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
 * An Adapter for {@link AsyncIOInterceptor}.
 *
 * @author Jeanfrancois Arcand
 */
public class AsyncIOInterceptorAdapter implements AsyncIOInterceptor {

    @Override
    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
    }

    @Override
    public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
        return responseDraft;
    }

    @Override
    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
    }

    @Override
    public byte[] error(AtmosphereResponse response, int statusCode, String reasonPhrase) {
        return null;
    }

    @Override
    public void redirect(AtmosphereResponse response, String location) {
    }
}
