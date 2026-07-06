# RUNTIME.md — Atmosphere extension

Runtime / model selection for this workspace's agents. Atmosphere applies
these `key: value` settings to the process-wide AiConfig at startup (last
workspace loaded wins — this is global config, not per-agent). OpenClaw
ignores this file.

These are **defaults**: an explicit `LLM_MODE` / `LLM_MODEL` / `LLM_API_KEY`
/ `LLM_BASE_URL` environment override (or the matching system property) wins
over the pins below, so you can point this sample at Ollama
(`LLM_MODE=local LLM_MODEL=qwen2.5:3b`) without editing this file.

model: gemini-2.5-flash
temperature: 0.4
max-tokens: 2048
