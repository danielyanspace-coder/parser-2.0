'use strict';

/*
 * ALFA SMS — centralized control server (zero dependencies, Node 18+).
 *
 * Surfaces:
 *   1. /api/mini/*   — Telegram mini-app API. The user binds their access token
 *                      to their Telegram id (once), sets a GLOBAL schedule that
 *                      applies to all their devices (interval / windows / start),
 *                      manages devices (add via QR, active toggle) and per-device
 *                      PAYMENTS (each: requisites + amount + optional repeat
 *                      count). The global work switch starts every active device
 *                      instantly.
 *   2. /api/admin/*  — full admin panel, restricted to one Telegram id
 *                      (ADMIN_TG_ID). Manage tokens: create with expiry + device
 *                      quota, change quota, extend, enable/disable, unbind, delete.
 *   3. /api/device/* — the Android APK. It only scans a QR to pair, then
 *                      long-polls its desired state (schedule + payments) and
 *                      reports status. SMS always go to a fixed number (7878).
 *   4. /admin        — legacy password-protected HTML panel (break-glass).
 *
 * Storage: a single JSON file (data/db.json).
 *
 * Run:  ADMIN_PASSWORD=secret TELEGRAM_BOT_TOKEN=... node server.js
 */

const http = require('http');
const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

const PORT = parseInt(process.env.PORT || '8080', 10);
const ADMIN_USER = process.env.ADMIN_USER || 'admin';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '';
// The single Telegram id allowed into the in-app admin panel.
const ADMIN_TG_ID = String(process.env.ADMIN_TG_ID || '8211351879');
// Telegram bot token (BotFather). When set, initData signatures are verified.
const TELEGRAM_BOT_TOKEN = process.env.TELEGRAM_BOT_TOKEN || '';
// Fixed SMS recipient for every payment message.
const RECIPIENT_NUMBER = String(process.env.RECIPIENT_NUMBER || '7878');
const PAIRING_TTL_MS = parseInt(process.env.PAIRING_TTL_MS || String(10 * 60 * 1000), 10);
const LONGPOLL_TIMEOUT_MS = parseInt(process.env.LONGPOLL_TIMEOUT_MS || '25000', 10);
const SYNC_INTERVAL_MS = parseInt(process.env.SYNC_INTERVAL_MS || '15000', 10);
const PUBLIC_BASE_URL = (process.env.PUBLIC_BASE_URL || 'https://project.alfa-vpn.ru').replace(/\/+$/, '');

const DATA_DIR = path.join(__dirname, 'data');
const DB_FILE = path.join(DATA_DIR, 'db.json');
const PUBLIC_DIR = path.join(__dirname, 'public');
const APK_FILE = path.join(DATA_DIR, 'alfa-sms.apk');
const UPDATE_FILE = path.join(DATA_DIR, 'update.json');
const MAX_APK_BYTES = 150 * 1024 * 1024; // 150 MB upload cap

// Stable per-bot webhook secret (path + Telegram secret_token header).
const WEBHOOK_SECRET = TELEGRAM_BOT_TOKEN
  ? crypto.createHash('sha256').update('alfa-sms:' + TELEGRAM_BOT_TOKEN).digest('hex').slice(0, 32)
  : '';

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
    // Migrate any older records to the current shape.
    for (const t of db.tokens) if (!t.schedule) t.schedule = defaultSchedule();
    for (const d of db.devices) if (!Array.isArray(d.payments)) d.payments = [];
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
  fs.renameSync(tmp, DB_FILE);
}
function saveDbSoon() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try { saveDb(); } catch (e) { console.error('saveDb error:', e.message); }
  }, 500);
}

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function now() { return Date.now(); }
function uuid() { return crypto.randomUUID(); }

function newTokenValue() {
  const hex = crypto.randomBytes(10).toString('hex').toUpperCase();
  return hex.match(/.{1,5}/g).join('-');
}
function newPairingCode() {
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
    req.on('data', (c) => { data += c; if (data.length > maxBytes) { req.destroy(); reject(new Error('too_large')); } });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}
async function readJson(req) {
  const raw = await readBody(req);
  try { return JSON.parse(raw || '{}'); } catch (e) { return null; }
}
function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function fmtTime(ms) { return ms ? new Date(ms).toISOString().replace('T', ' ').slice(0, 19) + ' UTC' : '—'; }
function fmtDate(ms) { return ms ? new Date(ms).toISOString().slice(0, 10) : '∞'; }
function safeEqual(a, b) {
  const ba = Buffer.from(String(a)); const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

function tokenValid(t) {
  if (!t || !t.enabled) return false;
  if (t.expiresAt && now() >= t.expiresAt) return false;
  return true;
}
function findTokenByValue(v) { return db.tokens.find((t) => t.value === v); }
function findTokenByTelegram(id) { return db.tokens.find((t) => t.telegramId && String(t.telegramId) === String(id)); }
function tokenDevices(tokenId) { return db.devices.filter((d) => d.tokenId === tokenId); }

// Global (per-token) schedule that applies to every device under the token.
function defaultSchedule() {
  return { intervalSec: 15, windows: [], repeatDaily: false, startAtMillis: 0 };
}
function sanitizeSchedule(input) {
  const s = defaultSchedule();
  if (!input || typeof input !== 'object') return s;
  let iv = parseInt(input.intervalSec, 10);
  if (!Number.isFinite(iv) || iv < 1) iv = 15;
  s.intervalSec = Math.min(iv, 86400);
  s.windows = Array.isArray(input.windows)
    ? input.windows.map((w) => ({
        startSec: Math.max(0, Math.min(86399, parseInt(w.startSec, 10) || 0)),
        endSec: Math.max(0, Math.min(86399, parseInt(w.endSec, 10) || 0)),
      })).filter((w) => w.startSec !== w.endSec).slice(0, 20)
    : [];
  s.repeatDaily = Boolean(input.repeatDaily);
  const start = parseInt(input.startAtMillis, 10);
  s.startAtMillis = Number.isFinite(start) && start > 0 ? start : 0;
  return s;
}

// A payment block: requisites (message part 1) + amount (message part 2), and
// optionally repeated N times ("несколько платежей на этот реквизит с той же
// суммой"). Each repeat is one символ→Ок→успешно cycle.
// Normalizes a money amount to a canonical numeric string ("1 234,50" -> "1234.50").
function normalizeAmount(raw) {
  const s = String(raw == null ? '' : raw).trim().replace(/\s+/g, '').replace(',', '.');
  return s;
}
function isNumericAmount(raw) {
  const s = normalizeAmount(raw);
  return s.length > 0 && /^\d+(\.\d+)?$/.test(s) && parseFloat(s) > 0;
}
function amountValue(raw) {
  const s = normalizeAmount(raw);
  const n = parseFloat(s);
  return Number.isFinite(n) ? n : 0;
}
// Money formatting for reports: 1234.5 -> "1 234,5".
function fmtMoney(n) {
  const s = (Math.round(n * 100) / 100).toString().replace('.', ',');
  return s.replace(/\B(?=(\d{3})+(?![\d,]))/g, ' ');
}

// Sanitizes an incoming payments array. Empty/absent -> [] (device unconfigured).
function sanitizePayments(input) {
  if (!Array.isArray(input)) return [];
  return input.slice(0, 50).map((p, i) => {
    const multiple = Boolean(p && p.multiple);
    let count = parseInt(p && p.count, 10);
    if (!Number.isFinite(count) || count < 1) count = 1;
    return {
      id: (p && p.id) ? String(p.id).slice(0, 40) : uuid(),
      name: i === 0 ? 'Платеж' : `Платеж ${i + 1}`,
      requisites: String((p && p.requisites) || '').trim().slice(0, 1000),
      amount: normalizeAmount(p && p.amount).slice(0, 32),
      multiple,
      count: multiple ? Math.min(count, 100000) : 1,
    };
  });
}
// Validates payments: each block needs non-empty requisites and a numeric amount.
// Returns null if OK, or an error code string.
function validatePayments(list) {
  for (const p of list) {
    if (!p.requisites) return 'empty_requisites';
    if (!isNumericAmount(p.amount)) return 'invalid_amount';
  }
  return null;
}
// The message actually sent for a payment: requisites + " " + amount.
function paymentMessage(p) {
  return [String(p.requisites || '').trim(), String(p.amount || '').trim()].filter(Boolean).join(' ');
}

// ---------------------------------------------------------------------------
// Instant push to devices via long-polling.
// ---------------------------------------------------------------------------

const waiters = new Map(); // deviceId -> Set<{res,timer,secret}>

function deviceVersion(d, t) { return `${(t && t.rev) || 0}:${d.rev || 0}`; }

function wakeDevice(d) {
  const set = waiters.get(d.id);
  if (!set) return;
  waiters.delete(d.id);
  for (const w of set) { clearTimeout(w.timer); try { finishSync(w.res, d, w.secret); } catch (e) {} }
}
function bumpDevice(d) { d.rev = (d.rev || 0) + 1; wakeDevice(d); }
function bumpToken(t) { t.rev = (t.rev || 0) + 1; for (const d of tokenDevices(t.id)) wakeDevice(d); }

function buildSyncPayload(d, t) {
  const valid = tokenValid(t);
  const run = valid && !!(t && t.globalOn) && !!d.active;
  const sched = (t && t.schedule) || defaultSchedule();
  const payments = (d.payments || [])
    .map((p) => ({ requisites: p.requisites, amount: p.amount, count: p.multiple ? Math.max(1, p.count) : 1 }))
    .filter((p) => (p.requisites || p.amount));
  return {
    ok: true,
    version: deviceVersion(d, t),
    run,
    active: !!d.active,
    globalOn: !!(t && t.globalOn),
    tokenValid: valid,
    workSession: (t && t.workSession) || '',
    config: {
      number: RECIPIENT_NUMBER,
      intervalSec: sched.intervalSec,
      windows: sched.windows,
      repeatDaily: sched.repeatDaily,
      startAtMillis: sched.startAtMillis,
      payments,
      stopWord: 'символ',
      resumeWord: 'успешно',
      replyText: 'Ок',
    },
    syncIntervalMs: SYNC_INTERVAL_MS,
  };
}
function finishSync(res, d, secret) {
  if (!d.secret || !safeEqual(d.secret, secret)) return sendJson(res, 403, { error: 'unauthorized' });
  const t = db.tokens.find((x) => x.id === d.tokenId);
  d.lastSeen = now();
  saveDbSoon();
  return sendJson(res, 200, buildSyncPayload(d, t));
}

// ---------------------------------------------------------------------------
// Telegram Bot API (reports + "Доработать" flow)
// ---------------------------------------------------------------------------

function tgApi(method, params) {
  return new Promise((resolve) => {
    if (!TELEGRAM_BOT_TOKEN) return resolve(null);
    const body = JSON.stringify(params || {});
    const req = https.request({
      hostname: 'api.telegram.org',
      path: `/bot${TELEGRAM_BOT_TOKEN}/${method}`,
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
    }, (r) => {
      let d = '';
      r.on('data', (c) => (d += c));
      r.on('end', () => { try { resolve(JSON.parse(d)); } catch (e) { resolve(null); } });
    });
    req.on('error', (e) => { console.error('tgApi error:', e.message); resolve(null); });
    req.write(body);
    req.end();
  });
}
function tgSend(chatId, text, keyboard) {
  const params = { chat_id: chatId, text, parse_mode: 'HTML', disable_web_page_preview: true };
  if (keyboard) params.reply_markup = { inline_keyboard: keyboard };
  return tgApi('sendMessage', params);
}
function tgAnswerCallback(id, text) {
  return tgApi('answerCallbackQuery', { callback_query_id: id, text: text || '' });
}
function eschtml(s) {
  return String(s).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
}

// --- Report computation ---

// Per-device outcome for the current work session, from its reported progress.
function computeDeviceReport(d, t) {
  const P = d.payments || [];
  const same = d.status && d.status.workSession && d.status.workSession === t.workSession;
  const idx = same ? (d.status.paymentIndex || 0) : 0;
  const tc = same ? (d.status.triggerCount || 0) : 0;
  let executed = 0, target = 0;
  P.forEach((p, i) => {
    const amt = amountValue(p.amount);
    const cnt = p.multiple ? Math.max(1, p.count) : 1;
    target += amt * cnt;
    const done = i < idx ? cnt : (i === idx ? Math.min(tc, cnt) : 0);
    executed += amt * done;
  });
  return { name: d.name, executed, target, remaining: Math.max(0, target - executed) };
}

// Devices that took part in the session (paired, active, have payments).
function participatingDevices(t) {
  return tokenDevices(t.id).filter((d) => d.pairedAt && d.active && (d.payments || []).length > 0);
}
function sessionComplete(t) {
  if (!t.globalOn || !t.workSession) return false;
  const parts = participatingDevices(t);
  if (parts.length === 0) return false;
  return parts.every((d) => d.status && d.status.workSession === t.workSession && d.status.done);
}

function buildReportMessage(t) {
  const parts = participatingDevices(t);
  let totalExec = 0, totalTarget = 0, totalRem = 0;
  const lines = parts.map((d) => {
    const r = computeDeviceReport(d, t);
    totalExec += r.executed; totalTarget += r.target; totalRem += r.remaining;
    const mark = r.remaining <= 0 ? '🟢' : (r.executed > 0 ? '🟡' : '🔴');
    return `${mark} <b>${eschtml(r.name)}</b>\n` +
      `   🟢 Отправлено: <b>${fmtMoney(r.executed)}</b>\n` +
      `   🎯 Задача: <b>${fmtMoney(r.target)}</b>\n` +
      `   🔴 Не отработано: <b>${fmtMoney(r.remaining)}</b>`;
  });
  const header = totalRem <= 0
    ? '✅ <b>Работа завершена — всё отработано!</b>'
    : '📊 <b>Отчёт по работе устройств</b>';
  const body = lines.length ? lines.join('\n\n') : 'Нет устройств с настроенными платежами.';
  const totals =
    `\n\n━━━━━━━━━━━━━━\n` +
    `Σ Отправлено: <b>${fmtMoney(totalExec)}</b>\n` +
    `Σ Задача: <b>${fmtMoney(totalTarget)}</b>\n` +
    `Σ Не отработано: <b>${fmtMoney(totalRem)}</b>`;
  const note = totalRem > 0
    ? `\n\n💡 Чтобы доработать платежи, которые не были исполнены, нажмите кнопку ниже. ` +
      `Затем выставьте расписание — система сама уберёт уже отработанные платежи и настроит работу до опустошения балансов.`
    : '';
  const text = `${header}\n\n${body}${totals}${note}`;
  const keyboard = totalRem > 0 ? [[{ text: '🔧 Доработать', callback_data: `rw:${t.id}` }]] : null;
  return { text, keyboard };
}

// Sends the report once per work session to the token's Telegram user.
function maybeSendReport(t) {
  if (!t || !t.telegramId || !t.workSession) return;
  if (t.reportedSession === t.workSession) return;
  t.reportedSession = t.workSession;
  saveDb();
  const msg = buildReportMessage(t);
  tgSend(t.telegramId, msg.text, msg.keyboard);
}

// --- "Доработать": strip executed payments, keep only unexecuted ones ---

function reworkToken(t) {
  for (const d of tokenDevices(t.id)) {
    const P = d.payments || [];
    if (P.length === 0) continue;
    const same = d.status && d.status.workSession && d.status.workSession === t.workSession;
    if (!same) continue; // device didn't run this session — leave it untouched
    const idx = d.status.paymentIndex || 0;
    const tc = d.status.triggerCount || 0;
    const next = [];
    P.forEach((p, i) => {
      if (i < idx) return; // fully executed → drop
      if (i === idx) {
        const cnt = p.multiple ? Math.max(1, p.count) : 1;
        const remaining = cnt - Math.min(tc, cnt);
        if (remaining > 0) next.push({ ...p, multiple: remaining > 1, count: remaining });
        // else fully executed → drop
      } else {
        next.push(p); // not started → keep
      }
    });
    d.payments = sanitizePayments(next);
    d.status = {};
    bumpDevice(d);
  }
  t.globalOn = false;
  t.workSession = '';
  t.reportedSession = '';
  bumpToken(t);
  saveDb();
}

async function handleTelegramUpdate(update) {
  try {
    if (update.callback_query) {
      const cq = update.callback_query;
      const fromId = String(cq.from && cq.from.id);
      const data = String(cq.data || '');
      if (data.startsWith('rw:')) {
        const t = db.tokens.find((x) => x.id === data.slice(3));
        if (!t || String(t.telegramId) !== fromId) return tgAnswerCallback(cq.id, 'Недоступно');
        await tgAnswerCallback(cq.id);
        await tgSend(fromId,
          '❓ Настроить <b>доработку</b> автоматически?\n\nСистема удалит уже исполненные платежи со всех ваших устройств и оставит только неисполненные.',
          [[{ text: '✅ Да, настроить автоматически', callback_data: `rwc:${t.id}` }]]);
        return;
      }
      if (data.startsWith('rwc:')) {
        const t = db.tokens.find((x) => x.id === data.slice(4));
        if (!t || String(t.telegramId) !== fromId) return tgAnswerCallback(cq.id, 'Недоступно');
        reworkToken(t);
        await tgAnswerCallback(cq.id, 'Готово');
        await tgSend(fromId, '✅ <b>Всё настроено.</b>\n\nОткройте мини‑апп и настройте расписание для начала работы.');
        return;
      }
      return tgAnswerCallback(cq.id);
    }
    if (update.message && update.message.text) {
      const chatId = update.message.chat.id;
      const text = String(update.message.text).trim();
      if (text.startsWith('/start')) {
        await tgSend(chatId,
          '👋 <b>ALFA SMS</b>\n\nНажмите кнопку меню снизу, чтобы открыть панель управления устройствами.');
      }
    }
  } catch (e) {
    console.error('handleTelegramUpdate error:', e.message);
  }
}

function readRawBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => { size += c.length; if (size > maxBytes) { req.destroy(); reject(new Error('too_large')); return; } chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

// ---------------------------------------------------------------------------
// Public views for the mini-app
// ---------------------------------------------------------------------------

function pairingQrPayload(code) {
  return `alfasms://pair?u=${encodeURIComponent(PUBLIC_BASE_URL)}&c=${encodeURIComponent(code)}`;
}
function pendingPairing(d) {
  if (d.pairedAt || !d.pairing || now() >= d.pairing.expiresAt) return null;
  return { code: d.pairing.code, expiresAt: d.pairing.expiresAt, qr: pairingQrPayload(d.pairing.code) };
}
function deviceView(d) {
  const online = d.lastSeen && (now() - d.lastSeen < SYNC_INTERVAL_MS * 3);
  return {
    id: d.id, name: d.name, active: !!d.active, paired: !!d.pairedAt,
    online: !!online, lastSeen: d.lastSeen || 0, hardwareModel: d.hardwareModel || '',
    payments: d.payments || [], status: d.status || {}, pairing: pendingPairing(d),
  };
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
    schedule: t.schedule || defaultSchedule(),
    recipientNumber: RECIPIENT_NUMBER,
    devices: devices.map(deviceView),
  };
}

// ---------------------------------------------------------------------------
// Telegram auth
// ---------------------------------------------------------------------------

function verifyTelegramInitData(initData) {
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
    const user = params.get('user') ? JSON.parse(params.get('user')) : null;
    if (!user || !user.id) return null;
    return { telegramId: String(user.id), user };
  } catch (e) { return null; }
}
function resolveTelegramId(req) {
  const verified = verifyTelegramInitData(req.headers['x-init-data'] || '');
  if (verified) return verified.telegramId;
  if (!TELEGRAM_BOT_TOKEN) {
    const dbg = req.headers['x-debug-tg-id'];
    return dbg ? String(dbg) : 'dev-user';
  }
  return null;
}
function isAdminReq(req) {
  const id = resolveTelegramId(req);
  return id != null && String(id) === ADMIN_TG_ID;
}

// ---------------------------------------------------------------------------
// Legacy password admin panel (HTML)
// ---------------------------------------------------------------------------

function checkAdminAuth(req) {
  const header = req.headers['authorization'] || '';
  if (!header.startsWith('Basic ')) return false;
  let decoded;
  try { decoded = Buffer.from(header.slice(6), 'base64').toString('utf8'); } catch (e) { return false; }
  const idx = decoded.indexOf(':');
  return safeEqual(decoded.slice(0, idx), ADMIN_USER) && safeEqual(decoded.slice(idx + 1), ADMIN_PASSWORD);
}
function requireAdmin(req, res) {
  if (checkAdminAuth(req)) return true;
  res.writeHead(401, { 'WWW-Authenticate': 'Basic realm="admin", charset="UTF-8"', 'Content-Type': 'text/plain; charset=utf-8' });
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
    const status = t.enabled ? '<span class="ok">включён</span>' : '<span class="bad">выключен</span>';
    const exp = t.expiresAt
      ? (now() >= t.expiresAt ? `<span class="bad">истёк ${fmtDate(t.expiresAt)}</span>` : `до ${fmtDate(t.expiresAt)}`)
      : 'бессрочно';
    return `<tr>
      <td><code>${esc(t.value)}</code></td><td>${esc(t.comment || '')}</td>
      <td>${status}${t.globalOn ? ' <span class="work">▶</span>' : ''}</td><td>${exp}</td>
      <td>${devices.length} / ${t.deviceLimit || 0}</td>
      <td>${t.telegramId ? esc(String(t.telegramId)) : '—'}</td><td>${fmtTime(t.createdAt)}</td>
      <td class="actions">
        <form method="POST" action="/admin/quota"><input type="hidden" name="id" value="${esc(t.id)}"><input type="number" name="deviceLimit" value="${t.deviceLimit || 0}" min="0" style="width:60px"><button>Устр.</button></form>
        <form method="POST" action="/admin/extend"><input type="hidden" name="id" value="${esc(t.id)}"><input type="number" name="days" value="30" style="width:60px"><button>Дней</button></form>
        <form method="POST" action="/admin/toggle"><input type="hidden" name="id" value="${esc(t.id)}"><button>${t.enabled ? 'Выкл' : 'Вкл'}</button></form>
        <form method="POST" action="/admin/delete" onsubmit="return confirm('Удалить токен и устройства?')"><input type="hidden" name="id" value="${esc(t.id)}"><button class="danger">Удалить</button></form>
      </td></tr>`;
  }).join('');
  return `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>ALFA SMS — админка</title>
<style>body{font-family:system-ui,sans-serif;margin:24px;color:#202124}table{border-collapse:collapse;width:100%;margin-top:16px}th,td{border:1px solid #dadce0;padding:8px;font-size:13px;text-align:left}th{background:#f1f3f4}code{background:#f1f3f4;padding:2px 4px;border-radius:4px}button{cursor:pointer;padding:4px 8px}.danger{color:#c5221f}.ok{color:#137333;font-weight:600}.bad{color:#c5221f;font-weight:600}.work{color:#1a73e8}form.create{margin-top:8px;display:flex;gap:8px;flex-wrap:wrap;align-items:center}td.actions form{display:inline-flex;gap:3px;margin:1px}</style></head><body>
<h1>ALFA SMS — токены</h1>
<form class="create" method="POST" action="/admin/create"><input type="text" name="comment" placeholder="Комментарий" required><label>Дней:<input type="number" name="days" value="30" min="0" style="width:70px"></label><label>Устройств:<input type="number" name="deviceLimit" value="1" min="0" style="width:70px"></label><button>Создать токен</button></form>
<table><thead><tr><th>Токен</th><th>Комментарий</th><th>Статус</th><th>Срок</th><th>Устр.</th><th>Telegram</th><th>Создан</th><th>Действия</th></tr></thead>
<tbody>${rows || '<tr><td colspan="8">Пусто</td></tr>'}</tbody></table>
<p style="color:#5f6368;font-size:13px;margin-top:20px">Основная админка — в мини‑аппе (для Telegram ID ${esc(ADMIN_TG_ID)}). Эта страница — резервный доступ по паролю.</p>
</body></html>`;
}

// ---------------------------------------------------------------------------
// Routing helpers
// ---------------------------------------------------------------------------

function serveStatic(res, file, contentType) {
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': contentType });
    res.end(buf);
  });
}
function miniRequireToken(req, res) {
  const tgId = resolveTelegramId(req);
  if (!tgId) { sendJson(res, 401, { error: 'no_telegram_identity' }); return null; }
  const t = findTokenByTelegram(tgId);
  if (!t) { sendJson(res, 428, { error: 'need_token' }); return null; }
  return t;
}
function miniDevice(t, id) { return db.devices.find((d) => d.id === id && d.tokenId === t.id); }

function adminTokenSummary(t) {
  const devices = tokenDevices(t.id);
  return {
    id: t.id, value: t.value, comment: t.comment || '', enabled: !!t.enabled,
    expiresAt: t.expiresAt || 0, expired: !!(t.expiresAt && now() >= t.expiresAt),
    deviceLimit: t.deviceLimit || 0, deviceCount: devices.length,
    pairedCount: devices.filter((d) => d.pairedAt).length,
    activeCount: devices.filter((d) => d.active).length,
    telegramId: t.telegramId ? String(t.telegramId) : '',
    globalOn: !!t.globalOn, createdAt: t.createdAt || 0,
    schedule: t.schedule || defaultSchedule(),
    devices: devices.map((d) => ({
      id: d.id, name: d.name, active: !!d.active, paired: !!d.pairedAt,
      lastSeen: d.lastSeen || 0,
      payments: (d.payments || []).map((p) => ({
        name: p.name, requisites: p.requisites, amount: p.amount,
        multiple: !!p.multiple, count: p.multiple ? p.count : 1,
      })),
    })),
  };
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

const db = loadDb();

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const p = url.pathname;
  const m = req.method;

  try {
    // ================= Telegram webhook =================
    if (WEBHOOK_SECRET && m === 'POST' && p === `/bot/${WEBHOOK_SECRET}`) {
      if ((req.headers['x-telegram-bot-api-secret-token'] || '') !== WEBHOOK_SECRET) {
        res.writeHead(403); return res.end('forbidden');
      }
      const update = await readJson(req);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{"ok":true}');
      if (update) handleTelegramUpdate(update);
      return;
    }

    // ================= App updates (OTA, public) =================
    if (p === '/app/version.json' && m === 'GET') {
      let info = { versionCode: 0, versionName: '', notes: '' };
      if (fs.existsSync(UPDATE_FILE)) { try { info = JSON.parse(fs.readFileSync(UPDATE_FILE, 'utf8')); } catch (e) {} }
      return sendJson(res, 200, info);
    }
    if (p === '/app/alfa-sms.apk' && m === 'GET') {
      if (!fs.existsSync(APK_FILE)) { res.writeHead(404, { 'Content-Type': 'text/plain' }); return res.end('No APK published'); }
      const stat = fs.statSync(APK_FILE);
      res.writeHead(200, {
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': stat.size,
        'Content-Disposition': 'attachment; filename="alfa-sms.apk"',
      });
      return fs.createReadStream(APK_FILE).pipe(res);
    }

    // ================= Mini-app: session/state =================
    if (p === '/api/mini/state' && m === 'GET') {
      const tgId = resolveTelegramId(req);
      if (!tgId) return sendJson(res, 401, { error: 'no_telegram_identity' });
      const admin = String(tgId) === ADMIN_TG_ID;
      const t = findTokenByTelegram(tgId);
      if (!t) return sendJson(res, 200, { needToken: true, isAdmin: admin });
      return sendJson(res, 200, { needToken: false, isAdmin: admin, state: tokenStateView(t) });
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
      if (t.telegramId && String(t.telegramId) !== String(tgId)) return sendJson(res, 409, { error: 'bound_elsewhere' });
      for (const other of db.tokens) if (other !== t && String(other.telegramId) === String(tgId)) other.telegramId = null;
      t.telegramId = String(tgId);
      saveDb();
      return sendJson(res, 200, { state: tokenStateView(t), isAdmin: String(tgId) === ADMIN_TG_ID });
    }

    // ================= Admin API (Telegram-id gated) =================
    if (p.startsWith('/api/admin/')) {
      if (!isAdminReq(req)) return sendJson(res, 403, { error: 'forbidden' });

      if (p === '/api/admin/tokens' && m === 'GET') {
        return sendJson(res, 200, {
          adminTgId: ADMIN_TG_ID,
          totals: { tokens: db.tokens.length, devices: db.devices.length },
          tokens: db.tokens.map(adminTokenSummary),
        });
      }
      if (p === '/api/admin/token' && m === 'POST') {
        const body = await readJson(req);
        const days = parseInt(body && body.days, 10);
        const deviceLimit = Math.max(0, parseInt(body && body.deviceLimit, 10) || 0);
        const t = {
          id: uuid(), value: newTokenValue(), comment: String((body && body.comment) || '').slice(0, 200),
          enabled: true, createdAt: now(),
          expiresAt: Number.isFinite(days) && days > 0 ? now() + days * 86400000 : 0,
          deviceLimit, telegramId: null, globalOn: false, workSession: '', schedule: defaultSchedule(), rev: 0,
        };
        db.tokens.push(t);
        saveDb();
        return sendJson(res, 200, { token: adminTokenSummary(t) });
      }
      const am = p.match(/^\/api\/admin\/token\/([^/]+)(?:\/(\w+))?$/);
      if (am) {
        const t = db.tokens.find((x) => x.id === am[1]);
        if (!t) return sendJson(res, 404, { error: 'not_found' });
        const action = am[2] || '';
        if (m === 'DELETE' && !action) {
          db.tokens = db.tokens.filter((x) => x.id !== t.id);
          db.devices = db.devices.filter((x) => x.tokenId !== t.id);
          saveDb();
          return sendJson(res, 200, { ok: true });
        }
        if (m === 'POST' && action === 'quota') {
          const body = await readJson(req);
          t.deviceLimit = Math.max(0, parseInt(body && body.deviceLimit, 10) || 0);
          saveDb();
          return sendJson(res, 200, { token: adminTokenSummary(t) });
        }
        if (m === 'POST' && action === 'extend') {
          const body = await readJson(req);
          const days = parseInt(body && body.days, 10);
          t.expiresAt = Number.isFinite(days) && days > 0 ? now() + days * 86400000 : 0;
          bumpToken(t);
          saveDb();
          return sendJson(res, 200, { token: adminTokenSummary(t) });
        }
        if (m === 'POST' && action === 'toggle') {
          t.enabled = !t.enabled;
          bumpToken(t);
          saveDb();
          return sendJson(res, 200, { token: adminTokenSummary(t) });
        }
        if (m === 'POST' && action === 'unbind') {
          t.telegramId = null;
          saveDb();
          return sendJson(res, 200, { token: adminTokenSummary(t) });
        }
        return sendJson(res, 405, { error: 'method_not_allowed' });
      }
      return sendJson(res, 404, { error: 'not_found' });
    }

    // ================= Mini-app: authenticated endpoints =================
    if (p.startsWith('/api/mini/')) {
      const t = miniRequireToken(req, res);
      if (!t) return;

      if (p === '/api/mini/global' && m === 'POST') {
        if (!tokenValid(t)) return sendJson(res, 403, { error: 'token_invalid', state: tokenStateView(t) });
        const body = await readJson(req);
        const on = Boolean(body && body.on);
        const was = !!t.globalOn;
        t.globalOn = on;
        if (on) { t.workSession = uuid(); t.reportedSession = ''; }
        bumpToken(t);
        saveDb();
        // Turning work OFF ends the session → send the report of what got done.
        if (was && !on) maybeSendReport(t);
        return sendJson(res, 200, { state: tokenStateView(t) });
      }

      if (p === '/api/mini/schedule' && m === 'POST') {
        if (!tokenValid(t)) return sendJson(res, 403, { error: 'token_invalid', state: tokenStateView(t) });
        const body = await readJson(req);
        t.schedule = sanitizeSchedule(body && body.schedule);
        bumpToken(t); // schedule applies to all devices — push to all
        saveDb();
        return sendJson(res, 200, { state: tokenStateView(t) });
      }

      if (p === '/api/mini/device' && m === 'POST') {
        if (!tokenValid(t)) return sendJson(res, 403, { error: 'token_invalid', state: tokenStateView(t) });
        const body = await readJson(req);
        const name = String((body && body.name) || '').trim().slice(0, 60) || 'Устройство';
        if (tokenDevices(t.id).length >= (t.deviceLimit || 0)) {
          return sendJson(res, 409, { error: 'quota_exceeded', state: tokenStateView(t) });
        }
        const d = {
          id: uuid(), tokenId: t.id, name, active: true, createdAt: now(),
          pairedAt: 0, hardwareId: '', hardwareModel: '', secret: '', lastSeen: 0,
          payments: [], status: {},
          pairing: { code: newPairingCode(), expiresAt: now() + PAIRING_TTL_MS }, rev: 0,
        };
        db.devices.push(d);
        saveDb();
        return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t) });
      }

      const mdev = p.match(/^\/api\/mini\/device\/([^/]+)(?:\/(\w+))?$/);
      if (mdev) {
        const d = miniDevice(t, mdev[1]);
        if (!d) return sendJson(res, 404, { error: 'device_not_found' });
        const action = mdev[2] || '';

        if (m === 'DELETE' && !action) {
          db.devices = db.devices.filter((x) => x.id !== d.id);
          saveDb();
          return sendJson(res, 200, { state: tokenStateView(t) });
        }
        if (m === 'POST' && action === 'payments') {
          const body = await readJson(req);
          const clean = sanitizePayments(body && body.payments);
          const err = validatePayments(clean);
          if (err) return sendJson(res, 400, { error: err });
          d.payments = clean;
          bumpDevice(d);
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t) });
        }
        if (m === 'POST' && action === 'active') {
          const body = await readJson(req);
          d.active = Boolean(body && body.active);
          bumpDevice(d);
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
          d.pairedAt = 0; d.secret = ''; d.hardwareId = '';
          d.pairing = { code: newPairingCode(), expiresAt: now() + PAIRING_TTL_MS };
          bumpDevice(d);
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t) });
        }
        return sendJson(res, 405, { error: 'method_not_allowed' });
      }
      return sendJson(res, 404, { error: 'not_found' });
    }

    // ================= Device (APK) API =================
    if (p === '/api/device/pair' && m === 'POST') {
      const body = await readJson(req);
      if (!body) return sendJson(res, 400, { error: 'bad_json' });
      const code = String(body.code || '').trim().toUpperCase();
      const hardwareId = String(body.hardwareId || '').slice(0, 128);
      const model = String(body.model || '').slice(0, 80);
      if (!code) return sendJson(res, 400, { error: 'no_code' });
      const d = db.devices.find((x) => x.pairing && x.pairing.code === code && now() < x.pairing.expiresAt);
      if (!d) return sendJson(res, 404, { error: 'invalid_or_expired_code' });
      d.secret = newSecret(); d.hardwareId = hardwareId; d.hardwareModel = model;
      d.pairedAt = now(); d.lastSeen = now(); d.pairing = null;
      saveDb();
      return sendJson(res, 200, { deviceId: d.id, secret: d.secret, name: d.name, syncIntervalMs: SYNC_INTERVAL_MS });
    }

    if (p === '/api/device/sync' && m === 'POST') {
      const body = await readJson(req);
      if (!body) return sendJson(res, 400, { error: 'bad_json' });
      const secret = String(body.secret || '');
      const d = db.devices.find((x) => x.id === String(body.deviceId || ''));
      if (!d || !d.secret || !safeEqual(d.secret, secret)) return sendJson(res, 403, { error: 'unauthorized' });
      const t = db.tokens.find((x) => x.id === d.tokenId);
      d.lastSeen = now();
      if (body.status && typeof body.status === 'object') {
        d.status = {
          running: Boolean(body.status.running),
          sentCount: parseInt(body.status.sentCount, 10) || 0,
          paymentIndex: parseInt(body.status.paymentIndex, 10) || 0,
          triggerCount: parseInt(body.status.triggerCount, 10) || 0,
          paused: Boolean(body.status.paused),
          done: Boolean(body.status.done),
          workSession: String(body.status.workSession || ''),
          lastError: body.status.lastError ? String(body.status.lastError).slice(0, 200) : null,
          at: now(),
        };
      }
      saveDbSoon();

      // When every participating device has finished the session, stop and report.
      if (t && sessionComplete(t)) {
        t.globalOn = false;
        bumpToken(t);
        maybeSendReport(t);
        saveDb();
      }
      const current = deviceVersion(d, t);
      const wait = body.wait !== false;
      if (wait && String(body.version || '') === current) {
        let set = waiters.get(d.id);
        if (!set) { set = new Set(); waiters.set(d.id, set); }
        const entry = { res, secret, timer: null };
        entry.timer = setTimeout(() => {
          const s = waiters.get(d.id);
          if (s) { s.delete(entry); if (s.size === 0) waiters.delete(d.id); }
          try { finishSync(res, d, secret); } catch (e) {}
        }, LONGPOLL_TIMEOUT_MS);
        set.add(entry);
        req.on('close', () => {
          clearTimeout(entry.timer);
          const s = waiters.get(d.id);
          if (s) { s.delete(entry); if (s.size === 0) waiters.delete(d.id); }
        });
        return;
      }
      return sendJson(res, 200, buildSyncPayload(d, t));
    }

    // ================= Legacy password admin =================
    if (p === '/admin' && m === 'GET') {
      if (!requireAdmin(req, res)) return;
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      return res.end(renderAdmin());
    }
    if (p === '/admin/create' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const f = parseForm(await readBody(req));
      const days = parseInt(f.days, 10);
      db.tokens.push({
        id: uuid(), value: newTokenValue(), comment: String(f.comment || '').slice(0, 200),
        enabled: true, createdAt: now(),
        expiresAt: Number.isFinite(days) && days > 0 ? now() + days * 86400000 : 0,
        deviceLimit: Math.max(0, parseInt(f.deviceLimit, 10) || 0),
        telegramId: null, globalOn: false, workSession: '', schedule: defaultSchedule(), rev: 0,
      });
      saveDb();
      res.writeHead(302, { Location: '/admin' }); return res.end();
    }
    if (p === '/admin/quota' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const f = parseForm(await readBody(req));
      const t = db.tokens.find((x) => x.id === f.id);
      if (t) { t.deviceLimit = Math.max(0, parseInt(f.deviceLimit, 10) || 0); saveDb(); }
      res.writeHead(302, { Location: '/admin' }); return res.end();
    }
    if (p === '/admin/extend' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const f = parseForm(await readBody(req));
      const t = db.tokens.find((x) => x.id === f.id);
      if (t) { const days = parseInt(f.days, 10); t.expiresAt = Number.isFinite(days) && days > 0 ? now() + days * 86400000 : 0; bumpToken(t); saveDb(); }
      res.writeHead(302, { Location: '/admin' }); return res.end();
    }
    if (p === '/admin/toggle' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const f = parseForm(await readBody(req));
      const t = db.tokens.find((x) => x.id === f.id);
      if (t) { t.enabled = !t.enabled; bumpToken(t); saveDb(); }
      res.writeHead(302, { Location: '/admin' }); return res.end();
    }
    if (p === '/admin/delete' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      const f = parseForm(await readBody(req));
      db.tokens = db.tokens.filter((x) => x.id !== f.id);
      db.devices = db.devices.filter((x) => x.tokenId !== f.id);
      saveDb();
      res.writeHead(302, { Location: '/admin' }); return res.end();
    }

    // Publish a new APK + version (Basic-auth). Upload the APK, then announce.
    if (p === '/admin/apk' && m === 'PUT') {
      if (!requireAdmin(req, res)) return;
      let buf;
      try { buf = await readRawBody(req, MAX_APK_BYTES); } catch (e) { return sendJson(res, 413, { error: 'too_large' }); }
      ensureDataDir();
      fs.writeFileSync(APK_FILE, buf);
      return sendJson(res, 200, { ok: true, bytes: buf.length });
    }
    if (p === '/admin/release' && m === 'POST') {
      if (!requireAdmin(req, res)) return;
      let parsed;
      try { parsed = JSON.parse((await readBody(req)) || '{}'); } catch (e) { return sendJson(res, 400, { error: 'bad_json' }); }
      const info = {
        versionCode: parseInt(parsed.versionCode, 10) || 0,
        versionName: String(parsed.versionName || ''),
        notes: String(parsed.notes || ''),
      };
      ensureDataDir();
      fs.writeFileSync(UPDATE_FILE, JSON.stringify(info, null, 2));
      return sendJson(res, 200, { ok: true, published: info });
    }

    // ================= Static / root =================
    if ((p === '/' || p === '/app' || p === '/index.html') && m === 'GET') {
      return serveStatic(res, path.join(PUBLIC_DIR, 'miniapp.html'), 'text/html; charset=utf-8');
    }
    if (p === '/qrcode.js' && m === 'GET') {
      return serveStatic(res, path.join(PUBLIC_DIR, 'qrcode.js'), 'application/javascript; charset=utf-8');
    }
    if (p === '/health' && m === 'GET') return sendJson(res, 200, { ok: true });

    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Not found');
  } catch (e) {
    console.error('Request error:', e);
    sendJson(res, 500, { error: 'server_error' });
  }
});

server.listen(PORT, () => {
  console.log(`ALFA SMS central server on port ${PORT}`);
  console.log(`Mini-app / bot Web App URL: ${PUBLIC_BASE_URL}/`);
  console.log(`Legacy admin: http://localhost:${PORT}/admin (user: ${ADMIN_USER})`);
  console.log(`In-app admin Telegram id: ${ADMIN_TG_ID}`);
  console.log(`SMS recipient number: ${RECIPIENT_NUMBER}`);
  if (!TELEGRAM_BOT_TOKEN) {
    console.log('WARNING: TELEGRAM_BOT_TOKEN not set — initData NOT verified, bot reports disabled (dev mode).');
  } else {
    // Register the webhook so the bot delivers button presses to us. Idempotent.
    const url = `${PUBLIC_BASE_URL}/bot/${WEBHOOK_SECRET}`;
    tgApi('setWebhook', { url, secret_token: WEBHOOK_SECRET, allowed_updates: ['message', 'callback_query'] })
      .then((r) => console.log(`Telegram setWebhook -> ${url} : ${r && r.ok ? 'ok' : JSON.stringify(r)}`))
      .catch((e) => console.error('setWebhook failed:', e.message));
  }
});
