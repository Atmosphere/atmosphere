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
"""Atmosphere CrewAI sidecar — FastAPI service speaking the documented
HTTP+SSE wire protocol consumed by ``HttpSseSidecarClient`` on the Java side.

Public surface:

* :class:`atmosphere_crewai_bridge.app.SidecarApp` — exposes ``app`` factory.
* :data:`atmosphere_crewai_bridge.app.app` — pre-built FastAPI instance.
* :func:`atmosphere_crewai_bridge.crew_loader.load_crew_factory` — module:attr loader.

The wire protocol is documented in ``modules/crewai/README.md`` and pinned by
the Java bridge test ``CrewAiAgentRuntimeBridgeTest``. Do not change event
names or field shapes without updating both sides.
"""
from __future__ import annotations

__version__ = "0.1.0"

__all__ = ["__version__"]
