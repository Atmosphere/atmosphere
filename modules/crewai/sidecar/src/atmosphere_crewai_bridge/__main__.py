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
"""Console-script entry point for ``atmosphere-crewai-bridge``.

Run with::

    atmosphere-crewai-bridge --host 127.0.0.1 --port 8765 --crew my_crew:make_crew

The ``--crew`` argument is mandatory: it points at a Python attribute
that resolves to a CrewAI ``Crew`` (or a callable that builds one).
"""
from __future__ import annotations

import argparse
import logging
import sys

import uvicorn

from . import __version__
from .app import configure as configure_app
from .crew_loader import CrewLoadError

logger = logging.getLogger(__name__)


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="atmosphere-crewai-bridge",
        description=(
            "FastAPI sidecar speaking the Atmosphere CrewAI runtime "
            "wire protocol (HTTP + SSE)."
        ),
    )
    parser.add_argument(
        "--host", default="127.0.0.1",
        help="Bind address (default: 127.0.0.1)",
    )
    parser.add_argument(
        "--port", type=int, default=8765,
        help="Bind port (default: 8765)",
    )
    parser.add_argument(
        "--crew", required=True,
        help="Crew spec in 'module.path:attribute' form. The attribute "
             "must be a CrewAI Crew instance or a callable that returns one.",
    )
    parser.add_argument(
        "--log-level", default="info",
        choices=["critical", "error", "warning", "info", "debug", "trace"],
        help="Uvicorn log level (default: info)",
    )
    parser.add_argument(
        "--version", action="version",
        version=f"atmosphere-crewai-bridge {__version__}",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    # Configure logging early so crew-load errors are visible.
    logging.basicConfig(
        level=args.log_level.upper() if args.log_level != "trace" else "DEBUG",
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    try:
        configure_app(args.crew)
    except CrewLoadError as exc:
        # Print to stderr AND log — the user will see it from either
        # path. Exit non-zero so process supervisors notice.
        logger.error("failed to load crew spec %r: %s", args.crew, exc)
        print(f"error: {exc}", file=sys.stderr)
        return 2

    logger.info(
        "starting atmosphere-crewai-bridge %s on %s:%d (crew=%s)",
        __version__, args.host, args.port, args.crew,
    )
    # Pass the import string so uvicorn workers can reload it. Using
    # the already-configured module-level app keeps state attached.
    uvicorn.run(
        "atmosphere_crewai_bridge.app:app",
        host=args.host,
        port=args.port,
        log_level=args.log_level if args.log_level != "trace" else "debug",
        access_log=False,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
