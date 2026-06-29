# PERMISSIONS.md — Atmosphere extension

Content governance for the assistant. Each `deny:` / `deny-regex:` /
`allow:` / `allow-regex:` / `require-role:` directive below compiles to a
GovernancePolicy that Atmosphere installs framework-wide and enforces on
every turn — web chat, A2A, and channels alike. OpenClaw ignores this file.

The assistant must refuse to move money or surface secrets:

deny: wire money
deny: transfer my savings
deny-regex: (?i)\b(password|api[- ]?key)\b
