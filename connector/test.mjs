import assert from "node:assert/strict";
import { spawn } from "node:child_process";

const env = {
  ...process.env,
  PORT: "18787",
  BOSS_ADMIN_TOKEN: "admin-test",
  BOSS_DEVICE_TOKEN: "device-test"
};

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function fetchJson(url, options = {}) {
  const res = await fetch(url, options);
  const text = await res.text();
  let json;

  try {
    json = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(`Invalid JSON response from ${url}: ${text}`);
  }

  return { res, json };
}

async function withServer(testFn) {
  const child = spawn(
    process.execPath,
    ["server.js"],
    { cwd: new URL(".", import.meta.url), env, stdio: "inherit" }
  );

  try {
    await sleep(1000);
    await testFn();
  } finally {
    child.kill();
  }
}

await withServer(async () => {
  const health = await fetch("http://127.0.0.1:18787/health");
  assert.equal(health.status, 200, "health should be ok");

  const sms = await fetch("http://127.0.0.1:18787/v1/send_sms", {
    method: "POST",
    headers: {
      authorization: "Bearer admin-test",
      "content-type": "application/json"
    },
    body: JSON.stringify({
      to: "+4512345678",
      message: "Boss test"
    })
  });

  assert.equal(sms.status, 202, "SMS queue should be accepted");

  const actions = await fetch(
    "http://127.0.0.1:18787/v1/device/actions",
    { headers: { authorization: "Bearer device-test" } }
  );

  const data = await actions.json();
  assert.ok(data.actions?.length, "device should receive queued action");

  const action = data.actions[0];
  assert.equal(action.type, "send_sms");
  assert.equal(action.requires_confirmation, true);

  const approved = await fetch(
    `http://127.0.0.1:18787/v1/device/actions/${action.id}/approved`,
    { method: "POST", headers: { authorization: "Bearer device-test" } }
  );
  assert.equal(approved.status, 200, "device should approve send_sms");

  const completed = await fetch(
    `http://127.0.0.1:18787/v1/device/actions/${action.id}/completed`,
    { method: "POST", headers: { authorization: "Bearer device-test" } }
  );
  assert.equal(completed.status, 200, "device should complete approved action");

  const call = await fetch("http://127.0.0.1:18787/v1/start_call", {
    method: "POST",
    headers: {
      authorization: "Bearer admin-test",
      "content-type": "application/json"
    },
    body: JSON.stringify({ to: "+4512345678" })
  });
  const callData = await call.json();
  assert.equal(call.status, 202, "call action should be queued");

  const rejected = await fetch(
    `http://127.0.0.1:18787/v1/device/actions/${callData.action.id}/rejected`,
    { method: "POST", headers: { authorization: "Bearer device-test" } }
  );
  assert.equal(rejected.status, 200, "device should reject call action");

  const pendingAgain = await fetch(
    "http://127.0.0.1:18787/v1/device/actions",
    { headers: { authorization: "Bearer device-test" } }
  );
  const pendingData = await pendingAgain.json();
  assert.equal(pendingData.actions.length, 0, "rejected action should not remain pending");

  const init = await fetchJson("http://127.0.0.1:18787/mcp", {
    method: "POST",
    headers: {
      authorization: "Bearer admin-test",
      "content-type": "application/json"
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2024-11-05",
        capabilities: {},
        clientInfo: { name: "mcp-test", version: "1.0.0" }
      }
    })
  });

  assert.equal(init.res.status, 200, "MCP initialize should succeed");
  assert.ok(init.json.result?.serverInfo, "MCP initialize should include server metadata");

  const toolsList = await fetchJson("http://127.0.0.1:18787/mcp", {
    method: "POST",
    headers: {
      authorization: "Bearer admin-test",
      "content-type": "application/json"
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 2,
      method: "tools/list",
      params: {}
    })
  });

  assert.equal(toolsList.res.status, 200, "MCP tool listing should succeed");
  const names = toolsList.json.result.tools.map(t => t.name);
  assert.ok(names.includes("device_status"));
  assert.ok(names.includes("pending_actions"));
  assert.ok(names.includes("send_sms"));
  assert.ok(names.includes("start_call"));
  assert.ok(names.includes("open_app"));
  assert.ok(names.includes("open_chatgpt"));

  const deviceStatus = await fetchJson("http://127.0.0.1:18787/mcp", {
    method: "POST",
    headers: {
      authorization: "Bearer admin-test",
      "content-type": "application/json"
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: {
        name: "device_status",
        arguments: {}
      }
    })
  });

  assert.equal(deviceStatus.res.status, 200, "MCP device_status call should succeed");
  assert.ok(deviceStatus.json.result?.structuredContent, "MCP status tool should include structured content");

  const quizSms = await fetchJson("http://127.0.0.1:18787/mcp", {
    method: "POST",
    headers: {
      authorization: "Bearer admin-test",
      "content-type": "application/json"
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: {
        name: "send_sms",
        arguments: {
          to: "+4512345678",
          message: "MCP test"
        }
      }
    })
  });

  assert.equal(quizSms.res.status, 200, "MCP send_sms should succeed");
  assert.equal(quizSms.json.result.structuredContent.action.type, "send_sms");
  assert.equal(quizSms.json.result.structuredContent.action.requires_confirmation, true);

  console.log("Boss Device Connector tests passed");
});
