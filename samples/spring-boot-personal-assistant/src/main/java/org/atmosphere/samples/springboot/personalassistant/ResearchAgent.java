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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.runtime.TaskContext;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.websearch.WebSearchQuery;
import org.atmosphere.ai.websearch.WebSearchSupport;

/**
 * Research crew member — gathers background context on a topic via Atmosphere's
 * built-in {@code web_search} tool ({@link WebSearchSupport}).
 *
 * <p>The search engine is fail-closed: with no
 * {@code org.atmosphere.ai.websearch.endpoint} configured it returns a clear
 * "not configured" brief without touching the network, so the sample still runs
 * offline out of the box; point that property at a JSON search endpoint for live
 * results. Either way the primary assistant fans out through the
 * InMemoryProtocolBridge and receives a structured artifact — the point this
 * crew member demonstrates.</p>
 */
@Agent(
        name = "research-agent",
        skillFile = "skill:research-agent",
        description = "Summarizes background context on a topic so the primary assistant "
                + "can speak to it confidently.",
        version = "1.0.0",
        endpoint = "/atmosphere/a2a/research"
        // This agent is headless (@AgentSkill handlers, no @Prompt), so the
        // batteries-included harness() default does NOT apply here — the
        // harness completes prompt-loop agents only. The sample's harness
        // consumer is the UpstreamMcpAgent @AiEndpoint (harness = {ALL}).
)
public class ResearchAgent {

    @AgentSkill(id = "summarize_topic", name = "Summarize Topic",
            description = "Return a short research brief on a single topic.",
            tags = {"research", "summary"})
    @AgentSkillHandler
    public void summarizeTopic(TaskContext task,
                               @AgentSkillParam(name = "topic",
                                       description = "The topic to research") String topic) {
        // The built-in web_search tool: pluggable engine, fail-closed offline.
        var results = WebSearchSupport.shared().search(WebSearchQuery.of(topic));
        var report = "Research brief for: '" + topic + "'\n\n" + results.toModelText();
        task.addArtifact(Artifact.text(report));
        task.complete(results.available()
                ? "Summary generated from web search"
                : "Web search is not configured");
    }
}
