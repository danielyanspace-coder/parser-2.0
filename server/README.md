# Сервер управления ALFA SMS

Node.js‑сервер **без зависимостей** (только встроенные модули). Обслуживает три
поверхности: админку токенов, API мини‑аппа Telegram и API устройств (APK).
Данные хранятся в одном JSON‑файле `data/db.json`.

## Запуск

Нужен Node.js 18+.

```bash
cd server
ADMIN_PASSWORD='придумайте-пароль' \
PUBLIC_BASE_URL='https://alfa-vpn.ru' \
TELEGRAM_BOT_TOKEN='123456:AA...' \
node server.js
```

### Переменные окружения

| Переменная            | По умолчанию            | Назначение |
|-----------------------|-------------------------|------------|
| `ADMIN_PASSWORD`      | — (обязательно)         | Пароль входа в админку. |
| `ADMIN_USER`          | `admin`                 | Логин админки. |
| `PORT`                | `8080`                  | Порт. |
| `PUBLIC_BASE_URL`     | `https://alfa-vpn.ru`   | Публичный адрес сервера. Зашивается в QR‑код, по нему APK находит сервер. |
| `TELEGRAM_BOT_TOKEN`  | — (пусто)               | Токен бота из BotFather. Если задан — подпись Telegram `initData` проверяется (защита от подделки Telegram ID). Если пусто — проверка отключена (режим разработки). |
| `PAIRING_TTL_MS`      | `600000` (10 мин)       | Срок жизни QR‑кода привязки. |
| `LONGPOLL_TIMEOUT_MS` | `25000`                 | Сколько сервер держит «спящий» long‑poll устройства до heartbeat‑ответа. |
| `SYNC_INTERVAL_MS`    | `15000`                 | Подсказка устройству о паузе между циклами при ошибке сети. |

При старте сервер печатает адрес админки и мини‑аппа.

## Мгновенная доставка команд (long‑polling)

Каждое привязанное устройство держит один открытый запрос `POST /api/device/sync`.
Сервер «паркует» его и отпускает **в момент любого изменения** нужного устройству
состояния: общий переключатель работы, флаг активности устройства или его
настройки. Поэтому «Включить все устройства в работу» доходит до телефонов за
миллисекунды, а не за период опроса. Изменения (`bumpToken` / `bumpDevice`)
будят все припаркованные запросы затронутых устройств.

## Telegram‑бот и мини‑апп

1. В [@BotFather](https://t.me/BotFather) создайте бота, получите токен.
2. Задайте боту Web App / кнопку меню с URL `https://alfa-vpn.ru/` (мини‑апп
   отдаётся по корню `/`).
3. Передайте серверу `TELEGRAM_BOT_TOKEN`.
4. Пользователь открывает мини‑апп, вводит токен один раз — его Telegram ID
   привязывается к токену, дальше вход автоматический.

## API

### Устройство (APK)

- `POST /api/device/pair` — `{code, hardwareId, model}` → `{deviceId, secret, name}`.
- `POST /api/device/sync` — `{deviceId, secret, version, wait, status}` →
  `{run, active, globalOn, tokenValid, workSession, config, stopWord, resumeWord, replyText, version}`.
  При `wait:true` и совпадении `version` запрос «висит» до изменения состояния.

### Мини‑апп (заголовок `X-Init-Data` с Telegram initData)

- `GET  /api/mini/state` — состояние токена и устройств (или `{needToken:true}`).
- `POST /api/mini/bind` — `{token}` привязать токен к Telegram‑аккаунту.
- `POST /api/mini/global` — `{on}` общий переключатель работы.
- `POST /api/mini/device` — `{name}` добавить устройство (вернёт код/QR).
- `POST /api/mini/device/:id/config` — сохранить настройки.
- `POST /api/mini/device/:id/active` — `{active}` активно/неактивно.
- `POST /api/mini/device/:id/name` — переименовать.
- `POST /api/mini/device/:id/pair` — новый QR‑код привязки.
- `DELETE /api/mini/device/:id` — удалить устройство (освободить слот).

### Админка (Basic‑auth)

- `GET  /admin` — список токенов, создание, лимит устройств, продление, вкл/выкл, удаление, отвязка TG.

## Хостинг

- **Обязательно HTTPS** — токены и команды идут по сети. На alfa‑vpn.ru
  поставьте reverse‑proxy (Caddy/Nginx) с сертификатом перед Node.
- Каталог `data/` (база токенов и устройств) **не коммитьте** — он в `.gitignore`.
  Делайте резервные копии: в нём вся привязка устройств и настройки.
- Для устойчивости long‑poll (25 с) убедитесь, что reverse‑proxy не рвёт
  соединение раньше (`proxy_read_timeout` ≥ 60s в Nginx).
