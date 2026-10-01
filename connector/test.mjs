import { spawn } from "node:child_process";

const env = {
  ...process.env,
  PORT: "18787",
  BOSS_ADMIN_TOKEN: "admin-test",
  BOSS_DEVICE_TOKEN: "device-test"
};

const child = spawn(
  process.execPath,
  ["server.js"],
  { cwd: new URL(".", import.meta.url), env, stdio: "inherit" }
);

const sleep = ms => new Promise(r => setTimeout(r, ms));

try {
  await sleep(1000);

  const health = await fetch("http://127.0.0.1:18787/health");
  if (!health.ok) throw new Error("health failed");

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

  if (sms.status !== 202) throw new Error("SMS queue failed");

  const actions = await fetch(
    "http://127.0.0.1:18787/v1/device/actions",
    { headers: { authorization: "Bearer device-test" } }
  );

  const data = await actions.json();

  if (!data.actions?.length) {
    throw new Error("Device did not receive action");
  }

  const action = data.actions[0];
  if (action.type !== "send_sms" || action.requires_confirmation !== true) {
    throw new Error("Queued SMS action is missing its approval requirement");
  }

  const approved = await fetch(
    `http://127.0.0.1:18787/v1/device/actions/${action.id}/approved`,
    { method: "POST", headers: { authorization: "Bearer device-test" } }
  );
  if (approved.status !== 200) throw new Error("Device could not approve action");

  const completed = await fetch(
    `http://127.0.0.1:18787/v1/device/actions/${action.id}/completed`,
    { method: "POST", headers: { authorization: "Bearer device-test" } }
  );
  if (completed.status !== 200) throw new Error("Device could not complete action");

  const call = await fetch("http://127.0.0.1:18787/v1/start_call", {
    method: "POST",
    headers: {
      authorization: "Bearer admin-test",
      "content-type": "application/json"
    },
    body: JSON.stringify({ to: "+4512345678" })
  });
  const callData = await call.json();
  const rejected = await fetch(
    `http://127.0.0.1:18787/v1/device/actions/${callData.action.id}/rejected`,
    { method: "POST", headers: { authorization: "Bearer device-test" } }
  );
  if (rejected.status !== 200) throw new Error("Device could not reject action");

  const pendingAgain = await fetch(
    "http://127.0.0.1:18787/v1/device/actions",
    { headers: { authorization: "Bearer device-test" } }
  );
  const pendingData = await pendingAgain.json();
  if (pendingData.actions.length !== 0) {
    throw new Error("Resolved actions were returned as pending");
  }

  console.log("Boss Device Connector tests passed");
} finally {
  child.kill();
}
