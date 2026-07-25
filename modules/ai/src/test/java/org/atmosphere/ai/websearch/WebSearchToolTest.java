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
package org.atmosphere.ai.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.atmosphere.ai.tool.ToolKind;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code web_search} tool and its {@link WebSearchEngine} SPI: it fails
 * closed (no network, clear notice) when unconfigured, formats a configured
 * engine's hits into the numbered block the model reads, resolves the
 * highest-priority <em>configured</em> engine, and never advertises itself when
 * offline (Correctness Invariants #5, #6).
 */
public class WebSearchToolTest {

    /** A configured stub engine returning fixed hits — no network involved. */
    private static WebSearchEngine stubEngine(int priority, WebSearchResult... hits) {
        return new WebSearchEngine() {
            @Override public String name() {
                return "stub";
            }
            @Override public boolean isConfigured() {
                return true;
            }
            @Override public int priority() {
                return priority;
            }
            @Override public WebSearchResults search(WebSearchQuery query) {
                return WebSearchResults.of(query.query(), name(), List.of(hits));
            }
        };
    }

    // ── Fail closed / offline ──

    @Test
    public void disabledEngineFailsClosedWithoutNetwork() {
        // A disabled HTTP engine has a blank endpoint: it must not be enabled and
        // must return a fail-closed notice rather than attempting any request.
        var support = new WebSearchSupport(new HttpWebSearchEngine(WebSearchConfig.disabled()));
        assertFalse(support.isEnabled(), "an unconfigured engine must not be enabled");

        var result = support.search(WebSearchQuery.of("atmosphere framework"));
        assertFalse(result.available());
        assertTrue(result.results().isEmpty());
        assertTrue(result.toModelText().toLowerCase().contains("not configured"),
                result.toModelText());
        assertTrue(result.toModelText().toLowerCase().contains("no web request"),
                result.toModelText());
    }

    @Test
    public void webSearchToolReturnsFailClosedNoticeWhenUnconfigured() throws Exception {
        // The shared instance reads system properties; by default no endpoint is
        // configured, so the tool executor returns the fail-closed notice.
        Assumptions.assumeFalse(WebSearchSupport.shared().isEnabled(),
                "web search endpoint is configured in this environment");

        var out = WebSearchTool.definition().executor()
                .execute(Map.of("query", "atmosphere framework"));
        assertTrue(out instanceof String);
        assertTrue(out.toString().toLowerCase().contains("not configured"), out.toString());
    }

    @Test
    public void httpEngineNeverTouchesNetworkWhenUnconfigured() {
        // An unroutable-looking endpoint left blank means isConfigured() == false,
        // so search() short-circuits before any HTTP client is even built.
        var engine = new HttpWebSearchEngine(WebSearchConfig.disabled());
        assertFalse(engine.isConfigured());
        var r = engine.search(WebSearchQuery.of("q"));
        assertFalse(r.available());
        assertTrue(r.message().toLowerCase().contains("not configured"), r.message());
    }

    // ── Configured engine: results are formatted for the model ──

    @Test
    public void configuredEngineResultsAreFormattedForTheModel() {
        var support = new WebSearchSupport(stubEngine(0,
                new WebSearchResult("Atmosphere 4 released", "https://async-io.org/a", "Real-time framework."),
                new WebSearchResult("WebSocket guide", "https://async-io.org/ws", "Transports explained.")));
        assertTrue(support.isEnabled());

        var text = support.search(WebSearchQuery.of("atmosphere", 5)).toModelText();
        assertTrue(text.contains("Web search results for: \"atmosphere\""), text);
        assertTrue(text.contains("[1] Atmosphere 4 released"), text);
        assertTrue(text.contains("URL: https://async-io.org/a"), text);
        assertTrue(text.contains("Real-time framework."), text);
        assertTrue(text.contains("[2] WebSocket guide"), text);
        assertTrue(text.contains("URL: https://async-io.org/ws"), text);
    }

    @Test
    public void configuredEngineWithNoHitsReportsNoResults() {
        var support = new WebSearchSupport(stubEngine(0));
        var text = support.search(WebSearchQuery.of("nothing here", 3)).toModelText();
        assertTrue(text.toLowerCase().contains("no web results"), text);
    }

    @Test
    public void engineExceptionIsReturnedAsFailClosedData() {
        WebSearchEngine boom = new WebSearchEngine() {
            @Override public String name() {
                return "boom";
            }
            @Override public boolean isConfigured() {
                return true;
            }
            @Override public WebSearchResults search(WebSearchQuery query) {
                throw new IllegalStateException("kaboom");
            }
        };
        var support = new WebSearchSupport(boom);
        var r = support.search(WebSearchQuery.of("q"));
        assertFalse(r.available());
        assertTrue(r.toModelText().contains("kaboom"), r.toModelText());
    }

    @Test
    public void blankQueryIsRejectedAsData() {
        var support = new WebSearchSupport(stubEngine(0));
        var r = support.search(WebSearchQuery.of("   ", 3));
        assertFalse(r.available());
        assertTrue(r.message().toLowerCase().contains("empty"), r.message());
    }

    @Test
    public void toolExecutorRejectsMissingQuery() throws Exception {
        var out = WebSearchTool.definition().executor().execute(Map.of());
        assertEquals("Error: 'query' is required", out);
    }

    // ── Tool definition metadata ──

    @Test
    public void toolIsNamedWebSearchAndTaggedNetwork() {
        var def = WebSearchTool.definition();
        assertEquals("web_search", def.name());
        assertEquals(WebSearchTool.TOOL_NAME, def.name());
        assertEquals(ToolKind.NETWORK, def.kind());
        assertTrue(def.parameters().stream().anyMatch(p -> p.name().equals("query") && p.required()));
        assertTrue(def.parameters().stream().anyMatch(p -> p.name().equals("num_results")));
    }

    // ── Pluggable engine SPI selection ──

    @Test
    public void highestPriorityConfiguredEngineWins() {
        var low = stubEngine(0, new WebSearchResult("low", "u", "s"));
        var high = stubEngine(10, new WebSearchResult("high", "u", "s"));
        var winner = WebSearchSupport.select(List.of(low, high));
        assertEquals(10, winner.priority());
        assertEquals("high", winner.search(WebSearchQuery.of("x")).results().get(0).title());
    }

    @Test
    public void unconfiguredEngineIsSkippedRegardlessOfPriority() {
        WebSearchEngine unconfiguredHighPriority = new WebSearchEngine() {
            @Override public String name() {
                return "down";
            }
            @Override public boolean isConfigured() {
                return false;   // credentials/endpoint absent
            }
            @Override public int priority() {
                return 100;     // would win if it were configured
            }
            @Override public WebSearchResults search(WebSearchQuery query) {
                return WebSearchResults.unavailable(query.query(), "unreachable");
            }
        };
        var configured = stubEngine(0, new WebSearchResult("t", "u", "s"));
        var winner = WebSearchSupport.select(List.of(unconfiguredHighPriority, configured));
        assertEquals("stub", winner.name());
    }

    @Test
    public void selectReturnsNullWhenNoEngineConfigured() {
        assertNull(WebSearchSupport.select(List.of()));
        assertNull(WebSearchSupport.select(List.of(
                new HttpWebSearchEngine(WebSearchConfig.disabled()))));
    }

    @Test
    public void resolveEngineFallsBackToFailClosedHttpEngine() {
        // With nothing configured, resolveEngine still yields a non-null engine so
        // the seam can answer fail-closed rather than NPE.
        var engine = WebSearchSupport.resolveEngine();
        assertTrue(engine != null);
    }

    // ── Argument parsing / bounds ──

    @Test
    public void parseCountHandlesNumbersStringsAndGarbage() {
        assertEquals(4, WebSearchTool.parseCount(4));
        assertEquals(4, WebSearchTool.parseCount("4"));
        assertEquals(WebSearchQuery.DEFAULT_MAX_RESULTS, WebSearchTool.parseCount(null));
        assertEquals(WebSearchQuery.DEFAULT_MAX_RESULTS, WebSearchTool.parseCount("not-a-number"));
    }

    @Test
    public void queryClampsResultCeilingIntoRange() {
        assertEquals(WebSearchQuery.HARD_CAP, WebSearchQuery.of("q", 9999).maxResults());
        assertEquals(WebSearchQuery.DEFAULT_MAX_RESULTS, WebSearchQuery.of("q", 0).maxResults());
        assertEquals(3, WebSearchQuery.of("q", 3).maxResults());
        assertEquals("trimmed", WebSearchQuery.of("  trimmed  ").query());
    }

    @Test
    public void configReadsEndpointAndDefaultsAndReportsConfigured() {
        var configured = new WebSearchConfig("https://search.example/api", "", null, null, 0, 0);
        assertTrue(configured.isConfigured());
        assertEquals(WebSearchConfig.DEFAULT_QUERY_PARAM, configured.queryParam());
        assertEquals(WebSearchConfig.DEFAULT_API_KEY_HEADER, configured.apiKeyHeader());
        assertFalse(WebSearchConfig.disabled().isConfigured());
    }
}
