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
package org.atmosphere.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.util.ExecutorsFactory;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KafkaBroadcaster} lifecycle management, producer/consumer
 * interactions, and resource cleanup using Mockito mocks.
 */
public class KafkaResourceLifecycleTest {

    private AtmosphereConfig config;
    private DefaultBroadcasterFactory factory;

    @BeforeEach
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        factory = new DefaultBroadcasterFactory();
    }

    @AfterEach
    public void tearDown() throws Exception {
        factory.destroy();
        ExecutorsFactory.reset(config);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testReleaseClosesProducerAndConsumer() throws Exception {
        var mockProducer = mock(KafkaProducer.class);
        var mockConsumer = mock(KafkaConsumer.class);

        // Set up consumer to return empty records, then throw WakeupException when shutting down
        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(java.util.Collections.emptyMap()))
                .thenThrow(new WakeupException());

        factory.configure(MockableKafkaBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        MockableKafkaBroadcaster.mockProducer = mockProducer;
        MockableKafkaBroadcaster.mockConsumer = mockConsumer;

        var broadcaster = (KafkaBroadcaster) factory.get(MockableKafkaBroadcaster.class, "lifecycle-test");

        broadcaster.releaseExternalResources();

        verify(mockConsumer).wakeup();
        verify(mockConsumer).close(any(Duration.class));
        verify(mockProducer).close(any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testReleaseHandlesConsumerCloseException() throws Exception {
        var mockProducer = mock(KafkaProducer.class);
        var mockConsumer = mock(KafkaConsumer.class);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(java.util.Collections.emptyMap()))
                .thenThrow(new WakeupException());

        doThrow(new RuntimeException("Consumer close failed")).when(mockConsumer).close(any(Duration.class));

        factory.configure(MockableKafkaBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        MockableKafkaBroadcaster.mockProducer = mockProducer;
        MockableKafkaBroadcaster.mockConsumer = mockConsumer;

        var broadcaster = (KafkaBroadcaster) factory.get(MockableKafkaBroadcaster.class, "lifecycle-err-test");

        // Should not throw even when consumer.close() fails
        broadcaster.releaseExternalResources();

        // Producer close should still be attempted
        verify(mockProducer).close(any(Duration.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testReleaseHandlesProducerCloseException() throws Exception {
        var mockProducer = mock(KafkaProducer.class);
        var mockConsumer = mock(KafkaConsumer.class);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(java.util.Collections.emptyMap()))
                .thenThrow(new WakeupException());

        doThrow(new RuntimeException("Producer close failed")).when(mockProducer).close(any(Duration.class));

        factory.configure(MockableKafkaBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        MockableKafkaBroadcaster.mockProducer = mockProducer;
        MockableKafkaBroadcaster.mockConsumer = mockConsumer;

        var broadcaster = (KafkaBroadcaster) factory.get(MockableKafkaBroadcaster.class, "producer-err-test");

        // Should not throw even when producer.close() fails
        broadcaster.releaseExternalResources();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPublishToKafkaSendsProducerRecord() throws Exception {
        var mockProducer = mock(KafkaProducer.class);
        var mockConsumer = mock(KafkaConsumer.class);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(java.util.Collections.emptyMap()))
                .thenThrow(new WakeupException());

        factory.configure(MockableKafkaBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        MockableKafkaBroadcaster.mockProducer = mockProducer;
        MockableKafkaBroadcaster.mockConsumer = mockConsumer;

        var broadcaster = (MockableKafkaBroadcaster) factory.get(MockableKafkaBroadcaster.class, "publish-test");

        broadcaster.publishToKafka("test-message");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture());

        var record = captor.getValue();
        assertEquals("atmosphere.publish-test", record.topic());
        assertEquals("test-message", new String((byte[]) record.value(), StandardCharsets.UTF_8));
        assertNotNull(record.headers().lastHeader(KafkaBroadcaster.NODE_ID_HEADER));

        broadcaster.releaseExternalResources();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testPublishToKafkaHandsSendException() throws Exception {
        var mockProducer = mock(KafkaProducer.class);
        var mockConsumer = mock(KafkaConsumer.class);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(java.util.Collections.emptyMap()))
                .thenThrow(new WakeupException());

        when(mockProducer.send(any())).thenThrow(new RuntimeException("Kafka unavailable"));

        factory.configure(MockableKafkaBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        MockableKafkaBroadcaster.mockProducer = mockProducer;
        MockableKafkaBroadcaster.mockConsumer = mockConsumer;

        var broadcaster = (MockableKafkaBroadcaster) factory.get(MockableKafkaBroadcaster.class, "send-err-test");

        // Should not throw - errors are logged and swallowed
        broadcaster.publishToKafka("will-fail");

        broadcaster.releaseExternalResources();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testProducerRecordIncludesCorrectKey() throws Exception {
        var mockProducer = mock(KafkaProducer.class);
        var mockConsumer = mock(KafkaConsumer.class);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(java.util.Collections.emptyMap()))
                .thenThrow(new WakeupException());

        factory.configure(MockableKafkaBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        MockableKafkaBroadcaster.mockProducer = mockProducer;
        MockableKafkaBroadcaster.mockConsumer = mockConsumer;

        var broadcaster = (MockableKafkaBroadcaster) factory.get(MockableKafkaBroadcaster.class, "key-test");

        broadcaster.publishToKafka("key-check");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture());

        // The key should be the broadcaster ID
        assertEquals("key-test", captor.getValue().key());

        broadcaster.releaseExternalResources();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testConsumerSubscribesToCorrectTopic() throws Exception {
        var mockProducer = mock(KafkaProducer.class);
        var mockConsumer = mock(KafkaConsumer.class);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(java.util.Collections.emptyMap()))
                .thenThrow(new WakeupException());

        factory.configure(MockableKafkaBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        MockableKafkaBroadcaster.mockProducer = mockProducer;
        MockableKafkaBroadcaster.mockConsumer = mockConsumer;

        factory.get(MockableKafkaBroadcaster.class, "subscribe-test");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(mockConsumer).subscribe(captor.capture());
        assertEquals(List.of("atmosphere.subscribe-test"), captor.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testNodeIdHeaderValueMatchesBroadcasterNodeId() throws Exception {
        var mockProducer = mock(KafkaProducer.class);
        var mockConsumer = mock(KafkaConsumer.class);

        when(mockConsumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(java.util.Collections.emptyMap()))
                .thenThrow(new WakeupException());

        factory.configure(MockableKafkaBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);

        MockableKafkaBroadcaster.mockProducer = mockProducer;
        MockableKafkaBroadcaster.mockConsumer = mockConsumer;

        var broadcaster = (MockableKafkaBroadcaster) factory.get(MockableKafkaBroadcaster.class, "header-test");

        broadcaster.publishToKafka("check-header");

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture());

        var header = captor.getValue().headers().lastHeader(KafkaBroadcaster.NODE_ID_HEADER);
        assertNotNull(header);
        var headerNodeId = new String(header.value(), StandardCharsets.UTF_8);
        assertEquals(broadcaster.getNodeId(), headerNodeId);

        broadcaster.releaseExternalResources();
    }

    /**
     * A KafkaBroadcaster subclass that uses injectable mock producer/consumer.
     * Unlike TestableKafkaBroadcaster, this one keeps the real publishToKafka()
     * and startKafka() logic to verify Kafka client interactions.
     */
    public static class MockableKafkaBroadcaster extends KafkaBroadcaster {
        static KafkaProducer<String, byte[]> mockProducer;
        static KafkaConsumer<String, byte[]> mockConsumer;

        @Override
        protected KafkaProducer<String, byte[]> createProducer(String bootstrapServers) {
            return mockProducer;
        }

        @Override
        protected KafkaConsumer<String, byte[]> createConsumer(String bootstrapServers, String groupId) {
            return mockConsumer;
        }
    }
}
