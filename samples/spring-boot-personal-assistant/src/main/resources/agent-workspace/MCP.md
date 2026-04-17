# MCP.md — Per-user MCP servers (Atmosphere extension)

Each entry declares an MCP server the user wants the assistant to reach.
Credentials resolve through `ToolExtensibilityPoint.mcpCredential(userId,
server)` — the default trust provider is backed by the user's
`CredentialStore`.

- github: credential-store-backed
- gmail: oauth-delegated
