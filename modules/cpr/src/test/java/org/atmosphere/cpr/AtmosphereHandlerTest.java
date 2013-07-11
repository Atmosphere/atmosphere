/*
 * Copyright 2013 Jason Burgess
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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class AtmosphereHandlerTest {

    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;

    @Test
    public void testByteCachedList() throws Exception {
        AtmosphereFramework f = new AtmosphereFramework();
        f.setBroadcasterFactory(new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", f.getAtmosphereConfig()));
        assertNotNull(f.getBroadcasterFactory());
        broadcaster = f.getBroadcasterFactory().get(DefaultBroadcaster.class, "test");
        atmosphereHandler = new AR();

        final AtomicReference<byte[]> ref = new AtomicReference<byte[]>();
        AtmosphereResponse r = AtmosphereResponse.create();
        r.asyncIOWriter(new AsyncIOWriterAdapter() {
            @Override
            public AsyncIOWriter write(byte[] data) throws IOException {
                ref.set(data);
                return this;
            }
        });
        ar = new AtmosphereResourceImpl(f.getAtmosphereConfig(),
                broadcaster,
                mock(AtmosphereRequest.class),
                r,
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);


        broadcaster.addAtmosphereResource(ar);

        List<byte[]> l = new ArrayList<byte[]>();
        l.add("yo".getBytes());
        broadcaster.broadcast(l).get();
        assertEquals("yo", new String(ref.get()));
    }

    public final static class AR extends AbstractReflectorAtmosphereHandler {

        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }

        @Override
        public void destroy() {
        }
    }
}
