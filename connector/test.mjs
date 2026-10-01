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

  console.log("Boss Device Connector tests passed");
} finally {
  child.kill();
}
