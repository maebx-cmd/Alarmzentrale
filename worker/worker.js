// Alarmzentrale – Cloudflare Worker
// Bindung: D1-Datenbank als Variable "DB" (Settings → Bindings → D1).
// Tabellen werden beim ersten Aufruf automatisch angelegt.

const PIN_SALT = "alarmzentrale-v1:";
const PIN_HASH = "29b7ffa71bf1086505fc836caa35af94b20c2027377b48e37a07e1f158be0491"; // SHA-256(SALT + PIN)
const DEVICE_KEY = "M6e_iIBbd2IgRQS8KQtDMOhF";   // muss mit der App übereinstimmen
const ONLINE_MS = 25_000;          // Gerät gilt als erreichbar, wenn letzte Abfrage jünger ist
const EXPIRE_MS = 15 * 60_000;     // nicht zugestellte Auslösungen verfallen nach 15 min
const MAX_FAILS = 10;              // falsche PINs pro 10 Minuten, danach Sperre
const FAIL_WINDOW = 10 * 60_000;

let ready = false;
async function init(db) {
  if (ready) return;
  await db.batch([
    db.prepare("CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT)"),
    db.prepare("CREATE TABLE IF NOT EXISTS cmd (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, created INTEGER NOT NULL, received INTEGER, stopped INTEGER, expired INTEGER)"),
    db.prepare("CREATE INDEX IF NOT EXISTS cmd_open ON cmd(received)"),
    db.prepare("CREATE TABLE IF NOT EXISTS fails (t INTEGER NOT NULL)"),
  ]);
  ready = true;
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
  "Cache-Control": "no-store",
};
const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json; charset=utf-8", ...CORS } });

async function sha256hex(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}

async function getKv(db) {
  const { results } = await db.prepare("SELECT k, v FROM kv").all();
  const o = {};
  for (const r of results) o[r.k] = r.v;
  return o;
}

async function lastCmd(db) {
  return await db.prepare("SELECT id, type, created, received, stopped, expired FROM cmd ORDER BY id DESC LIMIT 1").first();
}

export default {
  async fetch(req, env) {
    if (req.method === "OPTIONS") return new Response(null, { headers: CORS });
    const db = env.DB;
    if (!db) return json({ error: "D1-Bindung 'DB' fehlt" }, 500);
    await init(db);

    const url = new URL(req.url);
    const now = Date.now();
    const path = url.pathname.replace(/\/+$/, "");

    try {
      // ---------- Statusseite ----------
      if (path === "/api/status" && req.method === "GET") {
        const kv = await getKv(db);
        const lastSeen = Number(kv.last_seen || 0);
        const fails = await db.prepare("SELECT COUNT(*) AS n FROM fails WHERE t > ?").bind(now - FAIL_WINDOW).first();
        return json({
          now,
          online: now - lastSeen < ONLINE_MS,
          lastSeen: lastSeen || null,
          battery: kv.battery != null ? Number(kv.battery) : null,
          charging: kv.charging === "1",
          ringing: kv.ringing === "1" && now - lastSeen < ONLINE_MS,
          locked: fails.n >= MAX_FAILS,
          last: await lastCmd(db),
        });
      }

      if (path === "/api/trigger" && req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        const type = body.type === "alarm" ? "alarm" : body.type === "wake" ? "wake" : null;
        if (!type) return json({ error: "Ungültiger Typ" }, 400);
        const fails = await db.prepare("SELECT COUNT(*) AS n FROM fails WHERE t > ?").bind(now - FAIL_WINDOW).first();
        if (fails.n >= MAX_FAILS) return json({ error: "Zu viele falsche PIN-Eingaben. Bitte 10 Minuten warten." }, 429);
        const pin = String(body.pin || "");
        if (!/^\d{6}$/.test(pin) || (await sha256hex(PIN_SALT + pin)) !== PIN_HASH) {
          await db.prepare("INSERT INTO fails (t) VALUES (?)").bind(now).run();
          return json({ error: "Falscher PIN-Code" }, 403);
        }
        const r = await db.prepare("INSERT INTO cmd (type, created) VALUES (?, ?)").bind(type, now).run();
        // Aufräumen: alte Einträge löschen
        await db.batch([
          db.prepare("DELETE FROM fails WHERE t < ?").bind(now - FAIL_WINDOW),
          db.prepare("DELETE FROM cmd WHERE id < (SELECT MAX(id) - 200 FROM cmd)"),
        ]);
        const kv = await getKv(db);
        return json({ ok: true, id: r.meta.last_row_id, online: now - Number(kv.last_seen || 0) < ONLINE_MS });
      }

      // ---------- Gerät ----------
      if (path === "/api/poll" && req.method === "GET") {
        if (url.searchParams.get("key") !== DEVICE_KEY) return json({ error: "forbidden" }, 403);
        const bat = url.searchParams.get("bat");
        const chg = url.searchParams.get("chg") === "1" ? "1" : "0";
        const ringing = url.searchParams.get("ringing") === "1" ? "1" : "0";
        await db.batch([
          db.prepare("INSERT INTO kv (k, v) VALUES ('last_seen', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").bind(String(now)),
          db.prepare("INSERT INTO kv (k, v) VALUES ('battery', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").bind(bat == null ? "" : String(Number(bat) | 0)),
          db.prepare("INSERT INTO kv (k, v) VALUES ('charging', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").bind(chg),
          db.prepare("INSERT INTO kv (k, v) VALUES ('ringing', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v").bind(ringing),
          db.prepare("UPDATE cmd SET expired = ? WHERE received IS NULL AND expired IS NULL AND created < ?").bind(now, now - EXPIRE_MS),
        ]);
        const cmd = await db.prepare("SELECT id, type, created FROM cmd WHERE received IS NULL AND expired IS NULL ORDER BY id LIMIT 1").first();
        return json({ now, cmd: cmd || null });
      }

      if (path === "/api/ack" && req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        if (body.key !== DEVICE_KEY) return json({ error: "forbidden" }, 403);
        const id = Number(body.id);
        const t = Number(body.t) > 0 ? Math.min(Number(body.t), now) : now;
        if (!(id > 0)) return json({ ok: true }); // lokaler Test-Alarm
        if (body.state === "received") {
          await db.prepare("UPDATE cmd SET received = COALESCE(received, ?) WHERE id = ?").bind(t, id).run();
        } else if (body.state === "stopped") {
          await db.prepare("UPDATE cmd SET received = COALESCE(received, ?), stopped = COALESCE(stopped, ?) WHERE id = ?").bind(t, t, id).run();
        }
        return json({ ok: true });
      }

      if (path === "" || path === "/") return json({ service: "Alarmzentrale", ok: true });
      return json({ error: "not found" }, 404);
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 500);
    }
  },
};
