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
package org.atmosphere.ai.memory;

import org.atmosphere.ai.AiRequest;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.ContextProvider.Document;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticRecallInterceptorTest {

    private final AtmosphereResource resource = mock(AtmosphereResource.class);

    @Test
    void nullProviderReturnsUnchangedRequest() {
        var interceptor = new SemanticRecallInterceptor(null);
        var request = new AiRequest("hello", "prompt");

        var result = interceptor.preProcess(request, resource);

        assertEquals(request, result);
    }

    @Test
    void unavailableProviderReturnsUnchangedRequest() {
        var provider = mock(ContextProvider.class);
        when(provider.isAvailable()).thenReturn(false);
        var interceptor = new SemanticRecallInterceptor(provider);
        var request = new AiRequest("hello", "prompt");

        var result = interceptor.preProcess(request, resource);

        assertEquals(request, result);
        verify(provider, never()).retrieve(anyString(), anyInt());
    }

    @Test
    void availableProviderWithResultsAugmentsPrompt() {
        var provider = mock(ContextProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.transformQuery("hello")).thenReturn("hello");
        var docs = List.of(
                new Document("relevant info", "doc.md", 0.9),
                new Document("more info", "faq.md", 0.8)
        );
        when(provider.retrieve("hello", 5)).thenReturn(docs);
        when(provider.rerank("hello", docs)).thenReturn(docs);

        var interceptor = new SemanticRecallInterceptor(provider);
        var request = new AiRequest("hello", "system prompt");

        var result = interceptor.preProcess(request, resource);

        var expected = "system prompt\n\nRelevant context from past conversations:\n"
                + "relevant info [Source: doc.md]\n---\nmore info [Source: faq.md]";
        assertEquals(expected, result.systemPrompt());
    }

    @Test
    void emptyRetrievalReturnsUnchangedRequest() {
        var provider = mock(ContextProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.transformQuery("hello")).thenReturn("hello");
        when(provider.retrieve("hello", 5)).thenReturn(List.of());
        when(provider.rerank("hello", List.of())).thenReturn(List.of());

        var interceptor = new SemanticRecallInterceptor(provider);
        var request = new AiRequest("hello", "prompt");

        var result = interceptor.preProcess(request, resource);

        assertEquals(request, result);
    }

    @Test
    void exceptionInProviderReturnsUnchangedRequest() {
        var provider = mock(ContextProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.transformQuery("hello")).thenThrow(new RuntimeException("connection failed"));

        var interceptor = new SemanticRecallInterceptor(provider);
        var request = new AiRequest("hello", "prompt");

        var result = interceptor.preProcess(request, resource);

        assertEquals(request, result);
    }

    @Test
    void postProcessIsNoOp() {
        var provider = mock(ContextProvider.class);
        var interceptor = new SemanticRecallInterceptor(provider);
        var request = new AiRequest("hello", "prompt");

        interceptor.postProcess(request, resource);

        verify(provider, never()).retrieve(anyString(), anyInt());
    }

    @Test
    void nullProviderWarnsOnlyOnce() {
        var interceptor = new SemanticRecallInterceptor(null);
        var request = new AiRequest("hello", "prompt");

        var result1 = interceptor.preProcess(request, resource);
        var result2 = interceptor.preProcess(request, resource);

        // Both calls return unchanged request
        assertEquals(request, result1);
        assertEquals(request, result2);
    }

    @Test
    void customMaxResultsPassedToRetrieve() {
        var provider = mock(ContextProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.transformQuery("hello")).thenReturn("hello");
        when(provider.retrieve("hello", 3)).thenReturn(List.of());
        when(provider.rerank("hello", List.of())).thenReturn(List.of());

        var interceptor = new SemanticRecallInterceptor(provider, 3);
        var request = new AiRequest("hello", "prompt");

        interceptor.preProcess(request, resource);

        verify(provider).retrieve("hello", 3);
    }

    @Test
    void nullSystemPromptHandledGracefully() {
        var provider = mock(ContextProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.transformQuery("hello")).thenReturn("hello");
        var docs = List.of(new Document("context", "src.md", 0.95));
        when(provider.retrieve("hello", 5)).thenReturn(docs);
        when(provider.rerank("hello", docs)).thenReturn(docs);

        var interceptor = new SemanticRecallInterceptor(provider);
        var request = new AiRequest("hello", null, null, null,
                null, null, null, java.util.Map.of(), List.of());

        var result = interceptor.preProcess(request, resource);

        assertEquals("Relevant context from past conversations:\ncontext [Source: src.md]",
                result.systemPrompt());
    }

    @Test
    void transformedQueryUsedForRetrieval() {
        var provider = mock(ContextProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.transformQuery("hello")).thenReturn("transformed query");
        when(provider.retrieve("transformed query", 5)).thenReturn(List.of());
        when(provider.rerank("transformed query", List.of())).thenReturn(List.of());

        var interceptor = new SemanticRecallInterceptor(provider);
        var request = new AiRequest("hello", "prompt");

        interceptor.preProcess(request, resource);

        verify(provider).retrieve("transformed query", 5);
        verify(provider).rerank("transformed query", List.of());
    }
}
