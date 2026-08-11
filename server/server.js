'use strict';

/*
 * ALFA SMS — centralized control server (zero dependencies, Node 18+).
 *
 * Three surfaces:
 *   1. /admin        — password-protected panel. Create access tokens with an
 *                      expiry date and a device quota, add/remove devices to a
 *                      token, enable/disable (revoke) and delete tokens.
 *   2. /api/mini/*   — Telegram mini-app API. The user opens the mini-app, binds
 *                      their access token to their Telegram id (once), then
 *                      manages devices: add (with a QR pairing code), name,
 *                      configure ALL sending settings per device, toggle each
 *                      device active/inactive, and flip the global "put all
 *                      devices to work" switch.
 *   3. /api/device/* — the Android APK API. The APK is only a QR scanner: it
 *                      pairs with a device slot via the scanned code, then polls
 *                      /sync to receive its configuration and run-state and to
 *                      report status.
 *
 * Storage is a single JSON file (data/db.json); low volume, single process.
 *
 * Run:  ADMIN_PASSWORD=secret node server.js
 */

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const PORT = parseInt(process.env.PORT || '8080', 10);
const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '';
// Telegram bot token (from BotFather). When set, the mini-app's Telegram
// initData signature is verified so a Telegram id cannot be spoofed. When
// unset (local development) the check is skipped and X-Debug-Tg-Id is honored.
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || '';
// How long a QR pairing code stays valid.
const PAIRING_TTL_MS = parseInt(process.env.PAIRING_TTL_MS || String(10 * 60 * 1000), 10);
// Heartbeat timeout for the device long-poll: how long the server holds an
// idle /sync request open before answering with the (unchanged) current state.
const LONGPOLL_TIMEOUT_MS = parseInt(process.env.LONGPOLL_TIMEOUT_MS || '25000', 10);
// Advisory reconnect delay for the device between long-poll cycles / on error.
const SYNC_INTERVAL_MS = parseInt(process.env.SYNC_INTERVAL_MS || '15000', 10);
// Public base URL embedded into the QR so the APK knows which server to reach.
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || 'https://alfa-vpn.ru').replace(/\/+$/, '');

const DATA_DIR = path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const PUBLIC_DIR = path.join(__dirname, 'public');

if (!ADMIN_PASSWORD) {
  console.error('FATAL: set ADMIN_PASSWORD environment variable before starting.');
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Storage
// ---------------------------------------------------------------------------

function ensureDataDir() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
}

function loadDb() {
  ensureDataDir();
  if (!fs.existsSync(DB_FILE)) return { tokens: [], devices: [] };
  try {
    const db = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
    db.tokens = db.tokens || [];
    db.devices = db.devices || [];
    return db;
  } catch (e) {
    console.error('Failed to read db.json, starting empty:', e.message);
    return { tokens: [], devices: [] };
  }
}

let saveTimer = null;
function saveDb() {
  ensureDataDir();
  const tmp = DB_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(db, null, 2));
  fs.renameSync(tmp, DB_FILE); // atomic replace
}
// Coalesce frequent writes (device polling) into at most one write per tick.
function saveDbSoon() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try { saveDb(); } catch (e) { console.error('saveDb error:', e.message); }
  }, 500);
}

const db = loadDb();

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function now() { return Date.now(); }

function uuid() { return crypto.randomUUID(); }

function newTokenValue() {
  // 20 hex chars grouped for readability: XXXXX-XXXXX-XXXXX-XXXXX
  const hex = crypto.randomBytes(10).toString('hex').toUpperCase();
  return hex.match(/.{1,5}/g).join('-');
}

function newPairingCode() {
  // 8 unambiguous chars (no 0/O/1/I).
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let s = '';
  const bytes = crypto.randomBytes(8);
  for (let i = 0; i < 8; i++) s += alphabet[bytes[i] % alphabet.length];
  return s;
}

function newSecret() { return crypto.randomBytes(24).toString('hex'); }

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(body);
}

function readBody(req, maxBytes = 1e6) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (c) => {
      data += c;
      if (data.length > maxBytes) { req.destroy(); reject(new Error('too_large')); }
    });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

async function readJson(req) {
  const raw = await readBody(req);
  try { return JSON.parse(raw || '{}'); } catch (e) { return null; }
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function fmtTime(ms) {
  return ms ? new Date(ms).toISOString().replace('T', ' ').slice(0, 19) + ' UTC' : '—';
}

function fmtDate(ms) {
  return ms ? new Date(ms).toISOString().slice(0, 10) : '∞';
}

function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

// ---------------------------------------------------------------------------
// Domain helpers
// ---------------------------------------------------------------------------

function tokenValid(t) {
  if (!t || !t.enabled) return false;
  if (t.expiresAt && now() >= t.expiresAt) return false;
  return true;
}

function findTokenByValue(value) {
  return db.tokens.find((t) => t.value === value);
}

function findTokenByTelegram(tgId) {
  return db.tokens.find((t) => t.telegramId && String(t.telegramId) === String(tgId));
}

function tokenDevices(tokenId) {
  return db.devices.filter((d) => d.tokenId === tokenId);
}

function defaultConfig() {
  return {
    phone: '',
    message1: '',
    message2: '',
    intervalSec: 15,
    windows: [],          // [{startSec,endSec}]
    repeatDaily: false,
    startAtMillis: 0,     // 0 = as soon as work is on
    triggerLimit: 0,      // 0 = unlimited
  };
}

function sanitizeConfig(input) {
  const c = defaultConfig();
  if (!input || typeof input !== 'object') return c;
  c.phone = String(input.phone || '').slice(0, 40);
  c.message1 = String(input.message1 || '').slice(0, 1000);
  c.message2 = String(input.message2 || '').slice(0, 1000);
  let iv = parseInt(input.intervalSec, 10);
  if (!Number.isFinite(iv) || iv < 1) iv = 15;
  c.intervalSec = Math.min(iv, 86400);
  c.windows = Array.isArray(input.windows)
    ? input.windows
        .map((w) => ({
          startSec: Math.max(0, Math.min(86399, parseInt(w.startSec, 10) || 0)),
          endSec: Math.max(0, Math.min(86399, parseInt(w.endSec, 10) || 0)),
        }))
        .filter((w) => w.startSec !== w.endSec)
        .slice(0, 20)
    : [];
  c.repeatDaily = Boolean(input.repeatDaily);
  const start = parseInt(input.startAtMillis, 10);
  c.startAtMillis = Number.isFinite(start) && start > 0 ? start : 0;
  let lim = parseInt(input.triggerLimit, 10);
  if (!Number.isFinite(lim) || lim < 0) lim = 0;
  c.triggerLimit = Math.min(lim, 100000);
  return c;
}

// Public view of a device for the mini-app.
function deviceView(d) {
  const online = d.lastSeen && (now() - d.lastSeen < SYNC_INTERVAL_MS * 3);
  return {
    id: d.id,
    name: d.name,
    active: !!d.active,
    paired: !!d.pairedAt,
    online: !!online,
    lastSeen: d.lastSeen || 0,
    hardwareModel: d.hardwareModel || '',
    config: d.config,
    status: d.status || {},
    pairing: pendingPairing(d),
  };
}

function pendingPairing(d) {
  if (d.pairedAt) return null;
  if (!d.pairing) return null;
  if (now() >= d.pairing.expiresAt) return null;
  return {
    code: d.pairing.code,
    expiresAt: d.pairing.expiresAt,
    qr: pairingQrPayload(d.pairing.code),
  };
}

function pairingQrPayload(code) {
  // Compact custom URI the APK parses: server base + pairing code.
  return `alfasms://pair?u=${encodeURIComponent(PUBLIC_BASE_URL)}&c=${encodeURIComponent(code)}`;
}

function tokenStateView(t) {
  const devices = tokenDevices(t.id);
  return {
    comment: t.comment || '',
    expiresAt: t.expiresAt || 0,
    expired: !!(t.expiresAt && now() >= t.expiresAt),
    enabled: !!t.enabled,
    valid: tokenValid(t),
    deviceLimit: t.deviceLimit || 0,
    deviceCount: devices.length,
    canAddDevice: tokenValid(t) && devices.length < (t.deviceLimit || 0),
    globalOn: !!t.globalOn,
    devices: devices.map(deviceView),
  };
}

// ---------------------------------------------------------------------------
// Instant push to devices via long-polling.
//
// Each paired device holds one open /api/device/sync request. The server parks
// it and releases it the moment anything the device cares about changes (the
// global work switch, the device's active flag, or its configuration). That
// makes "put all devices to work" take effect within milliseconds instead of
// waiting for the next poll — pressing the switch is equivalent to the old
// app's "Now + Start", sending immediately.
// ---------------------------------------------------------------------------

const waiters = new Map(); // deviceId -> Set of { res, timer, secret }

// A monotonically-changing version so a device can tell whether its desired
// state moved since the last response. Bumped by bumpToken / bumpDevice.
function deviceVersion(d, t) {
  return `${(t && t.rev) || 0}:${d.rev || 0}`;
}

function wakeDevice(d) {
  const set = waiters.get(d.id);
  if (!set) return;
  waiters.delete(d.id);
  for (const w of set) {
    clearTimeout(w.timer);
    try { finishSync(w.res, d, w.secret); } catch (e) { /* connection gone */ }
  }
}

function bumpDevice(d) {
  d.rev = (d.rev || 0) + 1;
  wakeDevice(d);
}

function bumpToken(t) {
  t.rev = (t.rev || 0) + 1;
  for (const d of tokenDevices(t.id)) wakeDevice(d);
}

function buildSyncPayload(d, t) {
  const valid = tokenValid(t);
  const run = valid && !!(t && t.globalOn) && !!d.active;
  return {
    ok: true,
    version: deviceVersion(d, t),
    run,
    active: !!d.active,
    globalOn: !!(t && t.globalOn),
    tokenValid: valid,
    workSession: (t && t.workSession) || '',
    config: d.config,
    stopWord: 'символ',
    resumeWord: 'успешно',
    replyText: 'Ок',
    syncIntervalMs: SYNC_INTERVAL_MS,
  };
}

function finishSync(res, d, secret) {
  // Re-check auth in case the slot was re-paired while parked.
  if (!d.secret || !safeEqual(d.secret, secret)) {
    return sendJson(res, 403, { error: 'unauthorized' });
  }
  const t = db.tokens.find((x) => x.id === d.tokenId);
  d.lastSeen = now();
  saveDbSoon();
  return sendJson(res, 200, buildSyncPayload(d, t));
}

// ---------------------------------------------------------------------------
// Telegram mini-app auth (initData verification)
// ---------------------------------------------------------------------------

function verifyTelegramInitData(initData) {
  // Returns { telegramId, user } or null. When no bot token is configured we
  // cannot verify — the caller falls back to a debug id in that case.
  if (!initData || !TELEGRAM_BOT_TOKEN) return null;
  try {
    const params = new URLSearchParams(initData);
    const hash = params.get('hash');
    if (!hash) return null;
    params.delete('hash');
    const pairs = [];
    for (const [k, v] of params) pairs.push(`${k}=${v}`);
    pairs.sort();
    const dataCheckString = pairs.join('\n');
    const secretKey = crypto.createHmac('sha256', 'WebAppData').update(TELEGRAM_BOT_TOKEN).digest();
    const computed = crypto.createHmac('sha256', secretKey).update(dataCheckString).digest('hex');
    if (!safeEqual(computed, hash)) return null;
    const userRaw = params.get('user');
    const user = userRaw ? JSON.parse(userRaw) : null;
    if (!user || !user.id) return null;
    return { telegramId: String(user.id), user };
  } catch (e) {
    return null;
  }
}

// Resolve the Telegram identity of a mini-app request.
function resolveTelegramId(req) {
  const initData = req.headers['x-init-data'] || '';
  const verified = verifyTelegramInitData(initData);
  if (verified) return verified.telegramId;
  // Development fallback: only when the bot token is not configured.
  if (!TELEGRAM_BOT_TOKEN) {
    const dbg = req.headers['x-debug-tg-id'];
    return dbg ? String(dbg) : 'dev-user';
  }
  return null;
}

// ---------------------------------------------------------------------------
// Admin panel
// ---------------------------------------------------------------------------

function checkAdminAuth(req) {
  const header = req.headers['authorization'] || '';
  if (!header.startsWith('Basic ')) return false;
  let decoded;
  try { decoded = Buffer.from(header.slice(6), 'base64').toString('utf8'); } catch (e) { return false; }
  const idx = decoded.indexOf(':');
  const user = decoded.slice(0, idx);
  const pass = decoded.slice(idx + 1);
  return safeEqual(user, ADMIN_USER) && safeEqual(pass, ADMIN_PASSWORD);
}

function requireAdmin(req, res) {
  if (checkAdminAuth(req)) return true;
  res.writeHead(401, {
    'WWW-Authenticate': 'Basic realm="admin", charset="UTF-8"',
    'Content-Type': 'text/plain; charset=utf-8',
  });
  res.end('Требуется авторизация');
  return false;
}

function parseForm(body) {
  const params = new URLSearchParams(body);
  const obj = {};
  for (const [k, v] of params) obj[k] = v;
  return obj;
}

function renderAdmin() {
  const rows = db.tokens.map((t) => {
    const devices = tokenDevices(t.id);
    const status = t.enabled
      ? '<span class="ok">включён</span>'
      : '<span class="bad">выключен</span>';
    const exp = t.expiresAt
      ? (now() >= t.expiresAt
        ? `<span class="bad">истёк ${fmtDate(t.expiresAt)}</span>`
        : `до ${fmtDate(t.expiresAt)}`)
      : 'бессрочно';
    const activeDevices = devices.filter((d) => d.active).length;
    const pairedDevices = devices.filter((d) => d.pairedAt).length;
    return `<tr>
      <td><code>${esc(t.value)}</code></td>
      <td>${esc(t.comment || '')}</td>
      <td>${status}${t.globalOn ? ' <span class="work">▶ в работе</span>' : ''}</td>
      <td>${exp}</td>
      <td>${devices.length} / ${t.deviceLimit || 0}<br><small>привязано: ${pairedDevices}, активно: ${activeDevices}</small></td>
      <td>${t.telegramId ? esc(String(t.telegramId)) : '—'}</td>
      <td>${fmtTime(t.createdAt)}</td>
      <td class="actions">
        <form method="POST" action="/admin/quota">
          <input type="hidden" name="id" value="${esc(t.id)}">
          <input type="number" name="deviceLimit" value="${t.deviceLimit || 0}" min="0" style="width:64px">
          <button type="submit">Устройств</button>
        </form>
        <form method="POST" action="/admin/extend">
          <input type="hidden" name="id" value="${esc(t.id)}">
          <input type="number" name="days" value="30" style="width:64px" title="Продлить на N дней (0 = бессрочно)">
          <button type="submit">Продлить</button>
        </form>
        <form method="POST" action="/admin/toggle">
          <input type="hidden" name="id" value="${esc(t.id)}">
          <button type="submit">${t.enabled ? 'Выключить' : 'Включить'}</button>
        </form>
        ${t.telegramId ? `<form method="POST" action="/admin/unbind">
          <input type="hidden" name="id" value="${esc(t.id)}">
          <button type="submit" title="Отвязать Telegram-аккаунт">Отвязать TG</button>
        </form>` : ''}
        <form method="POST" action="/admin/delete" onsubmit="return confirm('Удалить токен и все его устройства безвозвратно?')">
          <input type="hidden" name="id" value="${esc(t.id)}">
          <button type="submit" class="danger">Удалить</button>
        </form>
      </td>
    </tr>`;
  }).join('');

  return `<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ALFA SMS — админка</title>
<style>
  body { font-family: system-ui, sans-serif; margin: 24px; color: #202124; background:#fff; }
  h1 { font-size: 22px; }
  table { border-collapse: collapse; width: 100%; margin-top: 16px; }
  th, td { border: 1px solid #dadce0; padding: 8px 10px; text-align: left; font-size: 13px; vertical-align: top; }
  th { background: #f1f3f4; }
  code { background: #f1f3f4; padding: 2px 4px; border-radius: 4px; }
  small { color:#5f6368; }
  button { cursor: pointer; padding: 4px 10px; margin:1px 0; }
  .danger { color:#c5221f; }
  .ok { color:#137333; font-weight:600; }
  .bad { color:#c5221f; font-weight:600; }
  .work { color:#1a73e8; font-weight:600; }
  form.create { margin-top: 8px; display: flex; gap: 8px; flex-wrap:wrap; align-items:center; }
  td.actions form { display:flex; gap:4px; margin-bottom:3px; align-items:center; }
  input[type=text], input[type=number] { padding: 6px 8px; }
  .hint { color: #5f6368; font-size: 13px; margin-top: 24px; line-height: 1.5; }
  label { font-size:13px; }
</style>
</head>
<body>
  <h1>ALFA SMS — токены доступа</h1>
  <form class="create" method="POST" action="/admin/create">
    <input type="text" name="comment" placeholder="Комментарий (кому выдан)" required>
    <label>Дней: <input type="number" name="days" value="30" min="0" style="width:70px" title="0 = бессрочно"></label>
    <label>Устройств: <input type="number" name="deviceLimit" value="1" min="0" style="width:70px"></label>
    <button type="submit">Создать токен</button>
  </form>
  <table>
    <thead>
      <tr><th>Токен</th><th>Комментарий</th><th>Статус</th><th>Срок</th>
      <th>Устройств (исп./лимит)</th><th>Telegram ID</th><th>Создан</th><th>Действия</th></tr>
    </thead>
    <tbody>${rows || '<tr><td colspan="8">Токенов пока нет</td></tr>'}</tbody>
  </table>
  <p class="hint">
    <b>Устройств</b> — сколько устройств пользователь может привязать (квота). Увеличьте, чтобы разрешить больше;
    уменьшите, чтобы забрать возможность добавлять новые (уже привязанные остаются, пока не удалит их сам пользователь).<br>
    <b>Продлить</b> — задаёт срок действия = сегодня + N дней (0 = бессрочно).<br>
    <b>Выключить</b> — отзывает токен: устройства перестают работать почти сразу (в течение периода опроса).
  </p>
</body>
</html>`;
}

// ---------------------------------------------------------------------------
// Routing
// ---------------------------------------------------------------------------

function serveStatic(res, file, contentType) {
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(buf);
  });
}

// --- mini-app helpers ---

function miniRequireToken(req, res) {
  // Returns the bound token or writes an error response and returns null.
  const tgId = resolveTelegramId(req);
  if (!tgId) { sendJson(res, 401, { error: 'no_telegram_identity' }); return null; }
  const t = findTokenByTelegram(tgId);
  if (!t) { sendJson(res, 428, { error: 'need_token' }); return null; }
  if (!tokenValid(t)) {
    // Still return the token so the mini-app can show an "expired/disabled" state.
    return t;
  }
  return t;
}

function miniDevice(t, id) {
  return db.devices.find((d) => d.id === id && d.tokenId === t.id);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const p = url.pathname;
  const m = req.method;

  try {
    // =====================================================================
    // Telegram mini-app API
    // =====================================================================
    if (p === '/api/mini/state' && m === 'GET') {
      const tgId = resolveTelegramId(req);
      if (!tgId) return sendJson(res, 401, { error: 'no_telegram_identity' });
      const t = findTokenByTelegram(tgId);
      if (!t) return sendJson(res, 200, { needToken: true });
      return sendJson(res, 200, { needToken: false, state: tokenStateView(t) });
    }

    if (p === '/api/mini/bind' && m === 'POST') {
      const tgId = resolveTelegramId(req);
      if (!tgId) return sendJson(res, 401, { error: 'no_telegram_identity' });
      const body = await readJson(req);
      if (!body) return sendJson(res, 400, { error: 'bad_json' });
      const value = String(body.token || '').trim().toUpperCase();
      const t = findTokenByValue(value);
      if (!t) return sendJson(res, 404, { error: 'invalid_token' });
      if (!t.enabled) return sendJson(res, 403, { error: 'disabled' });
      if (t.expiresAt && now() >= t.expiresAt) return sendJson(res, 403, { error: 'expired' });
      if (t.telegramId && String(t.telegramId) !== String(tgId)) {
        return sendJson(res, 409, { error: 'bound_elsewhere' });
      }
      // Detach this Telegram id from any other token it may have been on.
      for (const other of db.tokens) {
        if (other !== t && String(other.telegramId) === String(tgId)) other.telegramId = null;
      }
      t.telegramId = String(tgId);
      saveDb();
      return sendJson(res, 200, { state: tokenStateView(t) });
    }

    if (p.startsWith('/api/mini/')) {
      // All remaining mini endpoints require a bound token.
      const t = miniRequireToken(req, res);
      if (!t) return;

      if (p === '/api/mini/global' && m === 'POST') {
        const body = await readJson(req);
        if (!tokenValid(t)) return sendJson(res, 403, { error: 'token_invalid', state: tokenStateView(t) });
        const on = Boolean(body && body.on);
        t.globalOn = on;
        // Starting work opens a fresh work session so every device restarts its
        // counters (sent / triggers) instead of resuming a stale one.
        if (on) t.workSession = uuid();
        bumpToken(t); // release every parked device long-poll immediately
        saveDb();
        return sendJson(res, 200, { state: tokenStateView(t) });
      }

      if (p === '/api/mini/device' && m === 'POST') {
        if (!tokenValid(t)) return sendJson(res, 403, { error: 'token_invalid', state: tokenStateView(t) });
        const body = await readJson(req);
        const name = String((body && body.name) || '').trim().slice(0, 60) || 'Устройство';
        const devices = tokenDevices(t.id);
        if (devices.length >= (t.deviceLimit || 0)) {
          return sendJson(res, 409, { error: 'quota_exceeded', state: tokenStateView(t) });
        }
        const d = {
          id: uuid(),
          tokenId: t.id,
          name,
          active: true,
          createdAt: now(),
          pairedAt: 0,
          hardwareId: '',
          hardwareModel: '',
          secret: '',
          lastSeen: 0,
          config: defaultConfig(),
          status: {},
          pairing: { code: newPairingCode(), expiresAt: now() + PAIRING_TTL_MS },
        };
        db.devices.push(d);
        saveDb();
        return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t) });
      }

      // /api/mini/device/:id/...
      const mdev = p.match(/^\/api\/mini\/device\/([^/]+)(?:\/(\w+))?$/);
      if (mdev) {
        const d = miniDevice(t, mdev[1]);
        if (!d) return sendJson(res, 404, { error: 'device_not_found' });
        const action = mdev[2] || '';

        if (m === 'DELETE' && action === '') {
          db.devices = db.devices.filter((x) => x.id !== d.id);
          saveDb();
          return sendJson(res, 200, { state: tokenStateView(t) });
        }
        if (m === 'POST' && action === 'config') {
          const body = await readJson(req);
          d.config = sanitizeConfig(body && body.config);
          bumpDevice(d); // push new settings to the phone instantly
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t) });
        }
        if (m === 'POST' && action === 'active') {
          const body = await readJson(req);
          d.active = Boolean(body && body.active);
          bumpDevice(d); // start/stop this phone instantly
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t) });
        }
        if (m === 'POST' && action === 'name') {
          const body = await readJson(req);
          d.name = String((body && body.name) || '').trim().slice(0, 60) || d.name;
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t) });
        }
        if (m === 'POST' && action === 'pair') {
          // (Re)issue a pairing code, e.g. to move the slot to another phone.
          d.pairedAt = 0;
          d.secret = '';
          d.hardwareId = '';
          d.pairing = { code: newPairingCode(), expiresAt: now() + PAIRING_TTL_MS };
          bumpDevice(d); // drop the old phone's parked long-poll (now unauthorized)
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t) });
        }
        return sendJson(res, 405, { error: 'method_not_allowed' });
      }

      return sendJson(res, 404, { error: 'not_found' });
    }

    // =====================================================================
    // Device (APK) API
    // =====================================================================
    if (p === '/api/device/pair' && m === 'POST') {
      const body = await readJson(req);
      if (!body) return sendJson(res, 400, { error: 'bad_json' });
      const code = String(body.code || '').trim().toUpperCase();
      const hardwareId = String(body.hardwareId || '').slice(0, 128);
      const model = String(body.model || '').slice(0, 80);
      if (!code) return sendJson(res, 400, { error: 'no_code' });
      const d = db.devices.find(
        (x) => x.pairing && x.pairing.code === code && now() < x.pairing.expiresAt
      );
      if (!d) return sendJson(res, 404, { error: 'invalid_or_expired_code' });
      d.secret = newSecret();
      d.hardwareId = hardwareId;
      d.hardwareModel = model;
      d.pairedAt = now();
      d.lastSeen = now();
      d.pairing = null;
      saveDb();
      return sendJson(res, 200, {
        deviceId: d.id,
        secret: d.secret,
        name: d.name,
        syncIntervalMs: SYNC_INTERVAL_MS,
      });
    }

    if (p === '/api/device/sync' && m === 'POST') {
      const body = await readJson(req);
      if (!body) return sendJson(res, 400, { error: 'bad_json' });
      const secret = String(body.secret || '');
      const d = db.devices.find((x) => x.id === String(body.deviceId || ''));
      if (!d || !d.secret || !safeEqual(d.secret, secret)) {
        return sendJson(res, 403, { error: 'unauthorized' });
      }
      const t = db.tokens.find((x) => x.id === d.tokenId);
      d.lastSeen = now();

      // Store the status the device reports (for the mini-app to display).
      if (body.status && typeof body.status === 'object') {
        d.status = {
          running: Boolean(body.status.running),
          sentCount: parseInt(body.status.sentCount, 10) || 0,
          triggerCount: parseInt(body.status.triggerCount, 10) || 0,
          paused: Boolean(body.status.paused),
          lastError: body.status.lastError ? String(body.status.lastError).slice(0, 200) : null,
          at: now(),
        };
      }
      saveDbSoon();

      // Long-poll: if the device is already on the current version, hold the
      // request open until the state changes or a heartbeat timeout elapses.
      const current = deviceVersion(d, t);
      const wait = body.wait !== false; // default true
      if (wait && String(body.version || '') === current) {
        let set = waiters.get(d.id);
        if (!set) { set = new Set(); waiters.set(d.id, set); }
        const entry = { res, secret, timer: null };
        entry.timer = setTimeout(() => {
          const s = waiters.get(d.id);
          if (s) { s.delete(entry); if (s.size === 0) waiters.delete(d.id); }
          try { finishSync(res, d, secret); } catch (e) { /* gone */ }
        }, LONGPOLL_TIMEOUT_MS);
        set.add(entry);
        req.on('close', () => {
          clearTimeout(entry.timer);
          const s = waiters.get(d.id);
          if (s) { s.delete(entry); if (s.size === 0) waiters.delete(d.id); }
        });
        return; // response deferred
      }

      return sendJson(res, 200, buildSyncPayload(d, t));
    }

    // =====================================================================
    // Admin
    // =====================================================================
    if (p === '/admin' && m === 'GET') {
      if (!requireAdmin(req, res)) return;
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(renderAdmin());
    }

    if (p === '/admin/create' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const form = parseForm(await readBody(req));
      const days = parseInt(form.days, 10);
      const deviceLimit = Math.max(0, parseInt(form.deviceLimit, 10) || 0);
      db.tokens.push({
        id: uuid(),
        value: newTokenValue(),
        comment: String(form.comment || '').slice(0, 200),
        enabled: true,
        createdAt: now(),
        expiresAt: Number.isFinite(days) && days > 0 ? now() + days * 86400000 : 0,
        deviceLimit,
        telegramId: null,
        globalOn: false,
        workSession: '',
      });
      saveDb();
      res.writeHead(302, { Location: '/admin' });
      return res.end();
    }

    if (p === '/admin/quota' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const form = parseForm(await readBody(req));
      const t = db.tokens.find((x) => x.id === form.id);
      if (t) { t.deviceLimit = Math.max(0, parseInt(form.deviceLimit, 10) || 0); saveDb(); }
      res.writeHead(302, { Location: '/admin' });
      return res.end();
    }

    if (p === '/admin/extend' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const form = parseForm(await readBody(req));
      const t = db.tokens.find((x) => x.id === form.id);
      if (t) {
        const days = parseInt(form.days, 10);
        t.expiresAt = Number.isFinite(days) && days > 0 ? now() + days * 86400000 : 0;
        bumpToken(t); // expiry change takes effect on devices immediately
        saveDb();
      }
      res.writeHead(302, { Location: '/admin' });
      return res.end();
    }

    if (p === '/admin/toggle' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const form = parseForm(await readBody(req));
      const t = db.tokens.find((x) => x.id === form.id);
      if (t) { t.enabled = !t.enabled; bumpToken(t); saveDb(); } // instant revoke/enable
      res.writeHead(302, { Location: '/admin' });
      return res.end();
    }

    if (p === '/admin/unbind' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const form = parseForm(await readBody(req));
      const t = db.tokens.find((x) => x.id === form.id);
      if (t) { t.telegramId = null; saveDb(); }
      res.writeHead(302, { Location: '/admin' });
      return res.end();
    }

    if (p === '/admin/delete' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const form = parseForm(await readBody(req));
      db.tokens = db.tokens.filter((x) => x.id !== form.id);
      db.devices = db.devices.filter((x) => x.tokenId !== form.id);
      saveDb();
      res.writeHead(302, { Location: '/admin' });
      return res.end();
    }

    // =====================================================================
    // Mini-app static page + root
    // =====================================================================
    if ((p === '/' || p === '/app' || p === '/index.html') && m === 'GET') {
      return serveStatic(res, path.join(PUBLIC_DIR, 'miniapp.html'), 'text/html; charset=utf-8');
    }
    if (p === '/qrcode.js' && m === 'GET') {
      return serveStatic(res, path.join(PUBLIC_DIR, 'qrcode.js'), 'application/javascript; charset=utf-8');
    }
    if (p === '/health' && m === 'GET') {
      return sendJson(res, 200, { ok: true });
    }

    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
  } catch (e) {
    console.error('Request error:', e);
    sendJson(res, 500, { error: 'server_error' });
  }
});

server.listen(PORT, () => {
  console.log(`ALFA SMS central server listening on port ${PORT}`);
  console.log(`Admin panel:  http://localhost:${PORT}/admin  (user: ${ADMIN_USER})`);
  console.log(`Mini-app:     ${PUBLIC_BASE_URL}/  (set this as the bot Web App URL)`);
  console.log(`Public base:  ${PUBLIC_BASE_URL}  (embedded into QR codes)`);
  if (!TELEGRAM_BOT_TOKEN) {
    console.log('WARNING: TELEGRAM_BOT_TOKEN not set — Telegram initData is NOT verified (dev mode).');
  }
});
