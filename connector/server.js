import http from "node:http";
import crypto from "node:crypto";
import { URL } from "node:url";

const PORT = Number(process.env.PORT || 8787);
const ADMIN_TOKEN = process.env.BOSS_ADMIN_TOKEN || "";
const DEVICE_TOKEN = process.env.BOSS_DEVICE_TOKEN || "";
const MCP_PROTOCOL_VERSION = "2024-11-05";

const queue = [];
let lastSeen = null;
let lastDeviceStatus = {};

function json(res, status, body) {
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store"
  });
  res.end(JSON.stringify(body));
}

function mcpError(id, code, message, status = 200) {
  return { jsonrpc: "2.0", id, error: { code, message } };
}

function bearer(req) {
  const h = req.headers.authorization || "";
  return h.startsWith("Bearer ") ? h.slice(7) : "";
}

function safeEqual(a, b) {
  if (!a || !b) return false;
  const aa = Buffer.from(a);
  const bb = Buffer.from(b);
  return aa.length === bb.length && crypto.timingSafeEqual(aa, bb);
}

async function body(req) {
  let raw = "";
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 100000) throw new Error("body too large");
  }
  return raw ? JSON.parse(raw) : {};
}

function validPhone(value) {
  return typeof value === "string" &&
    /^[+0-9 ()-]{5,25}$/.test(value);
}

function addAction(type, payload) {
  const action = {
    id: crypto.randomUUID(),
    type,
    payload,
    created_at: new Date().toISOString(),
    status: "pending",
    requires_confirmation: true
  };
  queue.push(action);
  return action;
}

function toolDefinitions() {
  return [
    {
      name: "device_status",
      description: "Get current device status including online state and the latest sensor/telephony payload.",
      inputSchema: {
        type: "object",
        properties: {},
        additionalProperties: false
      }
    },
    {
      name: "pending_actions",
      description: "List the currently pending queued actions awaiting Android approval.",
      inputSchema: {
        type: "object",
        properties: {},
        additionalProperties: false
      }
    },
    {
      name: "send_sms",
      description: "Queue an SMS to be sent after confirmation by the Android device.",
      inputSchema: {
        type: "object",
        properties: {
          to: { type: "string", description: "Recipient phone number in E.164-style format." },
          message: { type: "string", description: "SMS text content." }
        },
        required: ["to", "message"],
        additionalProperties: false
      }
    },
    {
      name: "start_call",
      description: "Queue a call request that must be confirmed by the Android device before connecting.",
      inputSchema: {
        type: "object",
        properties: {
          to: { type: "string", description: "Destination phone number." }
        },
        required: ["to"],
        additionalProperties: false
      }
    },
    {
      name: "open_app",
      description: "Queue an app launch request that must be confirmed by the Android device before execution.",
      inputSchema: {
        type: "object",
        properties: {
          package_name: { type: "string", description: "Android package identifier to open." }
        },
        required: ["package_name"],
        additionalProperties: false
      }
    },
    {
      name: "open_chatgpt",
      description: "Queue a ChatGPT app launch request that must be confirmed by the Android device before execution.",
      inputSchema: {
        type: "object",
        properties: {},
        additionalProperties: false
      }
    }
  ];
}

function deviceStatusPayload() {
  return {
    online: !!lastSeen && Date.now() - new Date(lastSeen).getTime() < 120000,
    last_seen: lastSeen,
    device: lastDeviceStatus
  };
}

function handleToolCall(name, args) {
  switch (name) {
    case "device_status":
      return deviceStatusPayload();

    case "pending_actions":
      return {
        actions: queue.filter(x => x.status === "pending")
      };

    case "send_sms": {
      const b = args || {};
      if (!validPhone(b.to) || typeof b.message !== "string" || !b.message.trim() || b.message.length > 5000) {
        throw new Error("invalid_request");
      }
      return {
        action: addAction("send_sms", { to: b.to, message: b.message })
      };
    }

    case "start_call": {
      const b = args || {};
      if (!validPhone(b.to)) {
        throw new Error("invalid_request");
      }
      return {
        action: addAction("start_call", { to: b.to })
      };
    }

    case "open_app": {
      const b = args || {};
      if (typeof b.package_name !== "string" || !/^[A-Za-z0-9._]+$/.test(b.package_name)) {
        throw new Error("invalid_request");
      }
      return {
        action: addAction("open_app", { package_name: b.package_name })
      };
    }

    case "open_chatgpt":
      return {
        action: addAction("open_chatgpt", { package_name: "com.openai.chatgpt" })
      };

    default:
      throw new Error(`Unknown tool: ${name}`);
  }
}

const server = http.createServer(async (req, res) => {
  try {
    const u = new URL(req.url, `http://${req.headers.host}`);

    if (req.method === "GET" && u.pathname === "/health") {
      return json(res, 200, {
        ok: true,
        service: "Boss Device Connector"
      });
    }

    if (req.method === "GET" && u.pathname === "/mcp") {
      return json(res, 200, {
        ok: true,
        protocol: "mcp",
        endpoint: "/mcp"
      });
    }

    if (u.pathname === "/mcp") {
      if (req.method !== "POST") {
        return json(res, 405, {
          jsonrpc: "2.0",
          id: null,
          error: { code: -32000, message: "Method not allowed" }
        });
      }

      const auth = bearer(req);
      if (!safeEqual(auth, ADMIN_TOKEN)) {
        return json(res, 401, {
          jsonrpc: "2.0",
          id: null,
          error: { code: -32001, message: "Unauthorized" }
        });
      }

      let requestBody = {};
      try {
        requestBody = await body(req);
      } catch {
        return json(res, 400, {
          jsonrpc: "2.0",
          id: null,
          error: { code: -32700, message: "Parse error" }
        });
      }

      const id = requestBody.id ?? null;
      const method = requestBody.method;

      if (!method) {
        return json(res, 400, {
          jsonrpc: "2.0",
          id,
          error: { code: -32600, message: "Invalid Request" }
        });
      }

      if (method === "initialize") {
        return json(res, 200, {
          jsonrpc: "2.0",
          id,
          result: {
            protocolVersion: MCP_PROTOCOL_VERSION,
            capabilities: {
              tools: { listChanged: true }
            },
            serverInfo: {
              name: "boss-device-connector",
              version: "1.0.0"
            }
          }
        });
      }

      if (method === "notifications/initialized") {
        return json(res, 202, { jsonrpc: "2.0", id, result: {} });
      }

      if (method === "tools/list") {
        return json(res, 200, {
          jsonrpc: "2.0",
          id,
          result: { tools: toolDefinitions() }
        });
      }

      if (method === "tools/call") {
        try {
          const toolName = requestBody.params?.name;
          const toolArgs = requestBody.params?.arguments || {};
          const result = handleToolCall(toolName, toolArgs);
          return json(res, 200, {
            jsonrpc: "2.0",
            id,
            result: {
              content: [{
                type: "text",
                text: JSON.stringify(result, null, 2)
              }],
              structuredContent: result
            }
          });
        } catch (err) {
          return json(res, 200, {
            jsonrpc: "2.0",
            id,
            error: {
              code: -32603,
              message: err.message || "Tool execution failed"
            }
          });
        }
      }

      if (method === "ping") {
        return json(res, 200, {
          jsonrpc: "2.0",
          id,
          result: { ok: true }
        });
      }

      return json(res, 200, {
        jsonrpc: "2.0",
        id,
        error: { code: -32601, message: `Method not found: ${method}` }
      });
    }

    if (u.pathname.startsWith("/v1/device/")) {
      if (!safeEqual(bearer(req), DEVICE_TOKEN)) {
        return json(res, 401, { error: "unauthorized" });
      }

      lastSeen = new Date().toISOString();

      if (req.method === "GET" && u.pathname === "/v1/device/actions") {
        return json(res, 200, {
          actions: queue.filter(x => x.status === "pending")
        });
      }

      if (req.method === "POST" && u.pathname === "/v1/device/status") {
        lastDeviceStatus = await body(req);
        return json(res, 200, { ok: true });
      }

      const match = u.pathname.match(
        /^\/v1\/device\/actions\/([^/]+)\/(approved|rejected|completed)$/
      );

      if (req.method === "POST" && match) {
        const action = queue.find(x => x.id === match[1]);
        if (!action) return json(res, 404, { error: "not_found" });

        action.status = match[2];
        action.updated_at = new Date().toISOString();

        return json(res, 200, { ok: true, action });
      }

      return json(res, 404, { error: "not_found" });
    }

    if (!safeEqual(bearer(req), ADMIN_TOKEN)) {
      return json(res, 401, { error: "unauthorized" });
    }

    if (req.method === "GET" && u.pathname === "/v1/device/status") {
      return json(res, 200, deviceStatusPayload());
    }

    if (req.method === "GET" && u.pathname === "/v1/actions") {
      return json(res, 200, { actions: queue.slice(-100) });
    }

    if (req.method === "POST" && u.pathname === "/v1/send_sms") {
      const b = await body(req);

      if (!validPhone(b.to) || typeof b.message !== "string" ||
          !b.message.trim() || b.message.length > 5000) {
        return json(res, 400, { error: "invalid_request" });
      }

      return json(res, 202, {
        action: addAction("send_sms", {
          to: b.to,
          message: b.message
        })
      });
    }

    if (req.method === "POST" && u.pathname === "/v1/start_call") {
      const b = await body(req);

      if (!validPhone(b.to)) {
        return json(res, 400, { error: "invalid_request" });
      }

      return json(res, 202, {
        action: addAction("start_call", { to: b.to })
      });
    }

    if (req.method === "POST" && u.pathname === "/v1/open_app") {
      const b = await body(req);

      if (typeof b.package_name !== "string" ||
          !/^[A-Za-z0-9._]+$/.test(b.package_name)) {
        return json(res, 400, { error: "invalid_request" });
      }

      return json(res, 202, {
        action: addAction("open_app", {
          package_name: b.package_name
        })
      });
    }

    if (req.method === "POST" && u.pathname === "/v1/open_chatgpt") {
      return json(res, 202, {
        action: addAction("open_chatgpt", {
          package_name: "com.openai.chatgpt"
        })
      });
    }

    return json(res, 404, { error: "not_found" });

  } catch (e) {
    return json(res, 500, { error: "server_error" });
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Boss Device Connector listening on ${PORT}`);
});
