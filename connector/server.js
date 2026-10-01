import http from "node:http";
import crypto from "node:crypto";
import { URL } from "node:url";

const PORT = Number(process.env.PORT || 8787);
const ADMIN_TOKEN = process.env.BOSS_ADMIN_TOKEN || "";
const DEVICE_TOKEN = process.env.BOSS_DEVICE_TOKEN || "";

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

const server = http.createServer(async (req, res) => {
  try {
    const u = new URL(req.url, `http://${req.headers.host}`);

    if (req.method === "GET" && u.pathname === "/health") {
      return json(res, 200, {
        ok: true,
        service: "Boss Device Connector"
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
      return json(res, 200, {
        online: !!lastSeen &&
          Date.now() - new Date(lastSeen).getTime() < 120000,
        last_seen: lastSeen,
        device: lastDeviceStatus
      });
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
