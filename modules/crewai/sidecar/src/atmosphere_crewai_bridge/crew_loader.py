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
"""Parse ``module.path:attribute`` crew specs and instantiate the crew.

Factory contract (documented in the README):

The console script accepts ``--crew <module>:<attribute>``. The attribute
must resolve to ONE of the following:

1. A **callable** with one of two signatures::

        def factory() -> crewai.Crew: ...
        def factory(message: str, history: list[dict]) -> crewai.Crew: ...

   The bridge inspects the function signature and calls it with the
   matching arity.

2. A **``crewai.Crew`` instance** — used as-is.

In both cases the resulting crew is fed the user's ``message`` and
``history`` via ``crew.kickoff(inputs={...})``; templates in the user's
task descriptions should reference ``{message}`` and ``{history}``.
"""
from __future__ import annotations

import importlib
import inspect
import logging
from collections.abc import Callable
from typing import Any

logger = logging.getLogger(__name__)


class CrewLoadError(RuntimeError):
    """Raised when a crew spec cannot be parsed or imported."""


def parse_spec(spec: str) -> tuple[str, str]:
    """Split ``module.path:attribute`` into its parts.

    Reject specs with multiple ``:`` separators or empty segments —
    Invariant #4 (Boundary Safety): validate before interpretation.
    """
    if not isinstance(spec, str) or not spec.strip():
        raise CrewLoadError("crew spec is required (format: module.path:attribute)")
    parts = spec.split(":")
    if len(parts) != 2:
        raise CrewLoadError(
            f"crew spec must have exactly one ':' separator; got {spec!r}"
        )
    module_path, attr = parts[0].strip(), parts[1].strip()
    if not module_path or not attr:
        raise CrewLoadError(
            f"crew spec module and attribute must both be non-empty; got {spec!r}"
        )
    return module_path, attr


def load_crew_factory(spec: str) -> Callable[[str, list[dict[str, str]]], Any]:
    """Resolve ``spec`` to a callable that produces a configured ``Crew``.

    The returned callable always takes ``(message, history)`` regardless
    of the user's factory arity — we adapt at load time so the
    streaming bridge has a single calling convention.

    Returns:
        A callable ``(message: str, history: list[dict]) -> Crew``.

    Raises:
        CrewLoadError: spec malformed, module not importable, attribute
            missing, or attribute is not a Crew/callable.
    """
    module_path, attr = parse_spec(spec)
    try:
        module = importlib.import_module(module_path)
    except ImportError as exc:
        raise CrewLoadError(
            f"failed to import crew module {module_path!r}: {exc}"
        ) from exc
    if not hasattr(module, attr):
        raise CrewLoadError(
            f"module {module_path!r} has no attribute {attr!r}"
        )
    target = getattr(module, attr)

    if callable(target):
        return _wrap_callable(target, spec)

    # Not callable — accept it only if it walks/talks like a Crew. We
    # avoid an ``isinstance(target, Crew)`` check because we don't want
    # to import CrewAI at load time when the user's factory already did.
    if hasattr(target, "kickoff") or hasattr(target, "kickoff_async"):
        crew_instance = target
        logger.info("crew spec %r resolved to a pre-built Crew instance", spec)

        def from_instance(_message: str, _history: list[dict[str, str]]) -> Any:
            return crew_instance

        return from_instance

    raise CrewLoadError(
        f"crew spec {spec!r} resolved to {type(target).__name__!r} which is "
        "neither callable nor a Crew-like object (missing kickoff method)"
    )


def _wrap_callable(target: Callable[..., Any],
                   spec: str) -> Callable[[str, list[dict[str, str]]], Any]:
    """Inspect ``target`` and produce a uniform ``(message, history) ->
    Crew`` wrapper. Detects three accepted arities:

    * ``factory()``
    * ``factory(message)``
    * ``factory(message, history)``

    Anything else raises at load time so the failure surfaces before the
    first session POST.
    """
    try:
        sig = inspect.signature(target)
    except (TypeError, ValueError):
        # Built-in / C-extension callables may not have a signature; trust
        # the user and call with all args.
        def fallback(message: str, history: list[dict[str, str]]) -> Any:
            return target(message, history)

        return fallback

    positional = [
        p for p in sig.parameters.values()
        if p.kind in (inspect.Parameter.POSITIONAL_ONLY,
                      inspect.Parameter.POSITIONAL_OR_KEYWORD)
    ]
    has_var_positional = any(
        p.kind == inspect.Parameter.VAR_POSITIONAL
        for p in sig.parameters.values()
    )
    required = sum(1 for p in positional if p.default is inspect.Parameter.empty)

    if has_var_positional or len(positional) >= 2:
        def two_arg(message: str, history: list[dict[str, str]]) -> Any:
            return target(message, history)

        return two_arg
    if len(positional) == 1:
        def one_arg(message: str, _history: list[dict[str, str]]) -> Any:
            return target(message)

        return one_arg
    if required == 0:
        def zero_arg(_message: str, _history: list[dict[str, str]]) -> Any:
            return target()

        return zero_arg
    raise CrewLoadError(
        f"crew factory {spec!r} has an unsupported signature {sig}; "
        "expected (), (message), or (message, history)"
    )
