# Boss Device Connector

HTTPS/API bridge between ChatGPT and Boss Assistant.

## Security model

There are two independent bearer tokens:

- BOSS_ADMIN_TOKEN: used by the ChatGPT/tool side.
- BOSS_DEVICE_TOKEN: used only by the paired Android device.

Never commit real tokens to GitHub.

SMS and call requests are queued with `requires_confirmation=true`.
The Android client must explicitly approve them before execution.

## Endpoints

GET  /health
POST /mcp

Admin:
GET  /v1/device/status
GET  /v1/actions
POST /v1/send_sms
POST /v1/start_call
POST /v1/open_app
POST /v1/open_chatgpt

Device:
GET  /v1/device/actions
POST /v1/device/status
POST /v1/device/actions/{id}/approved
POST /v1/device/actions/{id}/rejected
POST /v1/device/actions/{id}/completed

## MCP endpoint

The connector exposes a JSON-RPC MCP endpoint at `/mcp` for remote ChatGPT tool usage.
Requests must use the `Authorization: Bearer <BOSS_ADMIN_TOKEN>` header.
The server supports initialize, tools/list, tools/call, and ping.
The tool set includes `device_status`, `pending_actions`, `send_sms`, `start_call`, `open_app`, and `open_chatgpt`.
All action-producing tools reuse the existing action queue and remain queued with `requires_confirmation=true` until the Android device approves them.
