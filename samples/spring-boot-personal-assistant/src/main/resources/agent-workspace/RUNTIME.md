# RUNTIME.md — Atmosphere extension

Runtime / model selection for this workspace's agents. Atmosphere applies
these `key: value` settings to the process-wide AiConfig at startup (last
workspace loaded wins — this is global config, not per-agent). OpenClaw
ignores this file.

model: gemini-2.5-flash
temperature: 0.4
max-tokens: 2048
