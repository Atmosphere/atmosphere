# PERMISSIONS.md — Default permission mode (Atmosphere extension)

default-mode: DEFAULT

per-tool:
  - name: propose_slots      # scheduler-agent
    requires-approval: false
  - name: summarize_topic    # research-agent
    requires-approval: false
  - name: draft_message      # drafter-agent
    requires-approval: false
  - name: send_message       # reserved for a future send-capable drafter
    requires-approval: true
