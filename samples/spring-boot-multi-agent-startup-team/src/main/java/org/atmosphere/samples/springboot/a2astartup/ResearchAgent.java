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
package org.atmosphere.samples.springboot.a2astartup;

import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.ai.websearch.WebSearchQuery;
import org.atmosphere.ai.websearch.WebSearchSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Independent Research Agent — searches the web through Atmosphere's built-in
 * {@code web_search} tool ({@link WebSearchSupport}). Discoverable at
 * {@code /atmosphere/a2a/research/agent.json}.
 *
 * <p>The search engine is fail-closed by default: with no
 * {@code org.atmosphere.ai.websearch.endpoint} configured it returns a clear
 * "not configured" brief without ever touching the network, so the sample runs
 * offline out of the box. Point that property at a JSON search endpoint (a
 * self-hosted metasearch instance or a hosted JSON search API) for live
 * results.</p>
 */
@Agent(
        name = "research-agent",
        skillFile = "skill:research-agent",
        description = "Web research agent that searches for market data, news, and competitor information",
        version = "1.0.0",
        endpoint = "/atmosphere/a2a/research"
)
public class ResearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(ResearchAgent.class);

    @AgentSkill(id = "web_search", name = "Web Search",
            description = "Search the web for market data, news, competitors, and trends. Returns relevant excerpts.",
            tags = {"research", "web", "search"})
    @AgentSkillHandler
    public void webSearch(TaskContext task,
                          @AgentSkillParam(name = "query", description = "Search query") String query,
                          @AgentSkillParam(name = "num_results", description = "Number of results (1-5)") String numResults) {
        task.updateStatus(TaskState.WORKING, "Searching the web for: " + query);
        logger.info("Research Agent: searching web for '{}'", query);

        int count;
        try {
            count = Math.min(5, Math.max(1, Integer.parseInt(numResults)));
        } catch (NumberFormatException e) {
            count = 3;
        }

        // The built-in web_search tool: pluggable engine, fail-closed offline.
        var results = WebSearchSupport.shared().search(WebSearchQuery.of(query, count));
        task.addArtifact(Artifact.text(results.toModelText()));
        task.complete(results.available()
                ? "Found " + results.results().size() + " result(s)"
                : "Web search is not configured");
    }
}
