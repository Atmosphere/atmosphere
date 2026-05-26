# Copyright 2008-2026 Async-IO.org
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Single-agent CrewAI factory wired to local Ollama.

Used by the e2e validation flow: launches a one-agent crew that answers the
user's message via qwen2.5:0.5b through Ollama. Picks the smallest model so
the chrome-devtools roundtrip finishes well inside the SSE timeout.

Run with::

    atmosphere-crewai-bridge --host 127.0.0.1 --port 8765 \\
        --crew examples.ollama_crew:make_crew
"""

from crewai import Agent, Crew, Process, Task
from crewai.llm import LLM


def make_crew() -> Crew:
    """Build the per-request Crew.

    The factory is invoked once per ``POST /v1/sessions`` so each request
    sees a fresh Crew (avoids state leaks between conversations). The
    sidecar threads the user message in as the ``{message}`` template
    placeholder on ``Task.description`` via ``Crew.kickoff(inputs=...)``.
    """
    llm = LLM(
        model="ollama/qwen2.5:0.5b",
        base_url="http://localhost:11434",
    )

    assistant = Agent(
        role="Research Assistant",
        goal="Give clear, concise answers to the user's question",
        backstory=(
            "You are a careful research assistant. Answer in one or two "
            "short sentences. Do not invent facts."
        ),
        llm=llm,
        verbose=False,
        allow_delegation=False,
    )

    task = Task(
        description="Respond to the user's question: {message}",
        expected_output="One or two short sentences answering the question.",
        agent=assistant,
    )

    return Crew(
        agents=[assistant],
        tasks=[task],
        process=Process.sequential,
        verbose=False,
    )
