/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.metrics;

import static org.mockito.Mockito.mock;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.*;
import org.atmosphere.util.ExecutorsFactory;
import org.mockito.Mockito;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class AtmosphereTracingTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;
    private Broadcaster broadcaster;
    private Tracer tracer;
    private SpanBuilder spanBuilder;
    private Span span;
    private Scope scope;

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");

        // Mock OTel tracer chain
        span = mock(Span.class);
        scope = mock(Scope.class);
        spanBuilder = mock(SpanBuilder.class);
        tracer = mock(Tracer.class);

        Mockito.when(tracer.spanBuilder(Mockito.anyString())).thenReturn(spanBuilder);
        Mockito.when(spanBuilder.setSpanKind(Mockito.any(SpanKind.class))).thenReturn(spanBuilder);
        Mockito.when(spanBuilder.setAttribute(Mockito.any(io.opentelemetry.api.common.AttributeKey.class), Mockito.anyString())).thenReturn(spanBuilder);
        Mockito.when(spanBuilder.startSpan()).thenReturn(span);
        Mockito.when(span.makeCurrent()).thenReturn(scope);
        Mockito.when(span.setAttribute(Mockito.any(io.opentelemetry.api.common.AttributeKey.class), Mockito.anyString())).thenReturn(span);
        Mockito.when(span.addEvent(Mockito.anyString())).thenReturn(span);
        Mockito.when(span.addEvent(Mockito.anyString(), Mockito.any(io.opentelemetry.api.common.Attributes.class))).thenReturn(span);
    }

    @AfterEach
    public void tearDown() throws Exception {
        broadcaster.destroy();
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    public void testInspectCreatesSpan() throws Exception {
        var tracing = new AtmosphereTracing(tracer);
        var resource = createResource();

        var action = tracing.inspect(resource);

        assertEquals(Action.CONTINUE, action);
        Mockito.verify(tracer).spanBuilder(Mockito.anyString());
        Mockito.verify(spanBuilder).startSpan();
        Mockito.verify(span).makeCurrent();
        assertNotNull(resource.getRequest().getAttribute(
                AtmosphereTracing.class.getName() + ".span"));
    }

    @Test
    public void testSpanHasTransportAttribute() throws Exception {
        var tracing = new AtmosphereTracing(tracer);
        var resource = createResource();

        tracing.inspect(resource);

        Mockito.verify(spanBuilder).setAttribute(
                Mockito.eq(io.opentelemetry.api.common.AttributeKey.stringKey("atmosphere.transport")),
                Mockito.anyString());
    }

    @Test
    public void testSpanHasUuidAttribute() throws Exception {
        var tracing = new AtmosphereTracing(tracer);
        var resource = createResource();

        tracing.inspect(resource);

        Mockito.verify(spanBuilder).setAttribute(
                Mockito.eq(io.opentelemetry.api.common.AttributeKey.stringKey("atmosphere.resource.uuid")),
                Mockito.eq(resource.uuid()));
    }

    @Test
    public void testPostInspectEndsSpanForNonSuspended() throws Exception {
        var tracing = new AtmosphereTracing(tracer);
        var resource = createResource();

        tracing.inspect(resource);
        tracing.postInspect(resource);

        Mockito.verify(span).end();
        Mockito.verify(scope).close();
    }

    @Test
    public void testConstructorWithOpenTelemetry() {
        var otel = OpenTelemetry.noop();
        var tracing = new AtmosphereTracing(otel);
        assertNotNull(tracing);
    }

    @SuppressWarnings("deprecation")
    private AtmosphereResource createResource() throws IOException {
        return new AtmosphereResourceImpl(config,
                broadcaster,
                AtmosphereRequestImpl.newInstance(),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                new AtmosphereHandler() {
                    @Override public void onRequest(AtmosphereResource resource) {}
                    @Override public void onStateChange(AtmosphereResourceEvent event) {}
                    @Override public void destroy() {}
                });
    }
}
