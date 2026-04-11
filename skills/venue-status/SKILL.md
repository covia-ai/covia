---
name: venue-status
description: Check Covia venue health — adapters, registered operations, active agents, workspace contents. Use for a quick overview of venue state.
---

# Venue Status

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`). If MCP tools are not available, tell the user to run `/venue-setup local` first.

Check the current state of the connected Covia venue. Run these in parallel:

1. **Adapters** — `covia_list path=v/info/adapters` — list all registered adapters and operation counts
2. **Agents** — `agent_list` — list all agents with status
3. **Workspace** — `covia_list` — describe the top-level namespace structure
4. **Operations catalog** — `covia_list path=v/ops` — count operations available in the venue catalog

Present a clean summary:

```
Venue Status
============
Adapters:    <count> registered (<names>)
Operations:  <count> available
Agents:      <count> (<statuses>)
Namespace:   <top-level keys>
```

If agents exist, show a table with agent ID, status, timeline length, and pending task count.

If the workspace has data, show the top-level keys under `w/`.
