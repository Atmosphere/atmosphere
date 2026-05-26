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
"""In-memory session registry with a hard size cap (backpressure) and
idempotent cancel (terminal-path completeness).

Correctness invariants applied here:

* **#3 Backpressure** — :meth:`SessionRegistry.acquire` returns ``None`` when
  the registry is full so the caller can return a 503 to the wire instead of
  silently growing the map.
* **#2 Terminal-path completeness** — :meth:`SessionRegistry.cancel` is
  idempotent: cancelling an unknown or already-cancelled id is a no-op and
  the HTTP layer still returns 204.
* **#1 Ownership** — :class:`Session` owns its cancel event and its event
  queue and exposes ``release()`` so the streaming generator (the caller
  that allocated the session) can remove it from the registry on its way
  out. The registry does not close queues it did not create.
"""
from __future__ import annotations

import asyncio
import logging
import threading
import uuid
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)

# Sentinel placed on the queue to signal "no more events". Using a unique
# object rather than ``None`` so callers that legitimately enqueue empty
# dicts cannot be confused with the close marker.
_QUEUE_CLOSED = object()


@dataclass
class Session:
    """A single sidecar-side session.

    The event queue carries already-rendered SSE frames (``bytes``) so the
    streaming endpoint can write them straight to the wire without a
    per-frame JSON encode on the hot path.
    """

    id: str
    queue: asyncio.Queue[object] = field(repr=False)
    cancelled: asyncio.Event = field(repr=False)
    # True once the producer has enqueued its terminal frame
    # (``done``/``error``) so a subsequent close call is a no-op.
    terminated: bool = False

    def request_cancel(self) -> bool:
        """Signal the producer to wind down. Idempotent.

        Returns ``True`` the first time the cancel flag transitions; ``False``
        on every subsequent call. Callers may use the return value for
        metrics but MUST treat both outcomes as success.
        """
        if self.cancelled.is_set():
            return False
        self.cancelled.set()
        return True

    def close_queue(self) -> None:
        """Push the queue-closed sentinel so the SSE consumer wakes up.

        Safe to call more than once: the consumer ignores the sentinel
        after the first read.
        """
        try:
            self.queue.put_nowait(_QUEUE_CLOSED)
        except asyncio.QueueFull:
            # Queue is bounded; if it's already full a previous frame is
            # still draining and the consumer will pick up the sentinel
            # right after. Logging at debug rather than swallowing.
            logger.debug("session %s queue full while closing; "
                         "consumer will drain remaining frames first", self.id)


class SessionRegistry:
    """Thread-safe (well, asyncio-safe) bounded session registry.

    The cap defends against an unbounded data structure fed by external
    input — Correctness Invariant #3. When the cap is hit, :meth:`acquire`
    returns ``None`` and the HTTP layer surfaces a 503.
    """

    DEFAULT_MAX_SESSIONS = 1024
    DEFAULT_QUEUE_SIZE = 256

    def __init__(self,
                 max_sessions: int = DEFAULT_MAX_SESSIONS,
                 queue_size: int = DEFAULT_QUEUE_SIZE) -> None:
        if max_sessions <= 0:
            raise ValueError("max_sessions must be > 0")
        if queue_size <= 0:
            raise ValueError("queue_size must be > 0")
        self._max_sessions = max_sessions
        self._queue_size = queue_size
        self._sessions: dict[str, Session] = {}
        # The registry is touched from FastAPI's worker tasks (async) as
        # well as the producer threads spun up for sync ``Crew.kickoff``
        # calls — a stdlib lock makes the mutation safe in both worlds.
        self._lock = threading.Lock()

    @property
    def size(self) -> int:
        with self._lock:
            return len(self._sessions)

    @property
    def capacity(self) -> int:
        return self._max_sessions

    def is_full(self) -> bool:
        with self._lock:
            return len(self._sessions) >= self._max_sessions

    def acquire(self) -> Session | None:
        """Create and register a new session. Returns ``None`` when full."""
        new_id = "sess_" + uuid.uuid4().hex
        session = Session(
            id=new_id,
            queue=asyncio.Queue(maxsize=self._queue_size),
            cancelled=asyncio.Event(),
        )
        with self._lock:
            if len(self._sessions) >= self._max_sessions:
                logger.warning(
                    "rejecting new session: registry at capacity %d/%d",
                    len(self._sessions), self._max_sessions,
                )
                return None
            self._sessions[new_id] = session
        return session

    def get(self, session_id: str) -> Session | None:
        with self._lock:
            return self._sessions.get(session_id)

    def cancel(self, session_id: str) -> bool:
        """Idempotent cancel.

        Returns ``True`` if the session existed and was newly cancelled,
        ``False`` if it was unknown or already cancelled. The HTTP layer
        does NOT use the return value to pick a status code — DELETE is
        always 204 (Invariant #2).
        """
        if not session_id:
            return False
        with self._lock:
            session = self._sessions.get(session_id)
        if session is None:
            logger.debug("cancel for unknown session id %s — no-op", session_id)
            return False
        newly = session.request_cancel()
        session.close_queue()
        return newly

    def release(self, session_id: str) -> None:
        """Remove a session from the registry. Called by the producer
        once it has emitted its terminal frame.
        """
        with self._lock:
            self._sessions.pop(session_id, None)


# Module-level singleton used by the FastAPI app. Tests construct their
# own instances against the application factory rather than mutating
# this global.
QUEUE_CLOSED = _QUEUE_CLOSED
