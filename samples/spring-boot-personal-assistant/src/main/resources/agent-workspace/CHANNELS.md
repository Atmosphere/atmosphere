# CHANNELS.md — Atmosphere extension

Atmosphere-only extension file — OpenClaw ignores this. Declares which
messaging channels the assistant reaches the user through.

- web (default)
- slack   # enable by setting SLACK_BOT_TOKEN
- telegram # enable by setting TELEGRAM_BOT_TOKEN

When multiple channels are configured, conversation continuity flows
across them via the shared session id — start a chat in Slack, resume in
the web UI.
