# Сервер управления ALFA SMS

Node.js без зависимостей (только встроенные модули). Обслуживает мини-апп
Telegram, админ-API, API устройств и резервную админку по паролю. Данные — в
`data/db.json`. Установка по шагам — в [../DEPLOY.md](../DEPLOY.md).

## Быстрый запуск (для разработки)
```bash
cd server
ADMIN_PASSWORD='пароль' PUBLIC_BASE_URL='https://project.alfa-vpn.ru' \
ADMIN_TG_ID='8211351879' TELEGRAM_BOT_TOKEN='...' node server.js
```

### Переменные окружения
| Переменная            | По умолчанию              | Назначение |
|-----------------------|---------------------------|------------|
| `ADMIN_PASSWORD`      | — (обязательно)           | Пароль резервной админки `/admin`. |
| `ADMIN_TG_ID`         | `8211351879`              | Telegram ID администратора (админка в мини-аппе). |
| `TELEGRAM_BOT_TOKEN`  | — (пусто)                 | Токен бота. Задан → проверяется подпись initData. Пусто → dev-режим. |
| `PUBLIC_BASE_URL`     | `https://project.alfa-vpn.ru` | Адрес сервиса, зашивается в QR-код. |
| `PORT`                | `8080`                    | Локальный порт (за nginx). |
| `RECIPIENT_NUMBER`    | `7878`                    | Номер получателя всех платёжных SMS. |
| `LONGPOLL_TIMEOUT_MS` | `25000`                   | Сколько держать «спящий» long-poll устройства. |

## Модель данных
- **Токен**: срок действия, лимит устройств, привязанный Telegram ID, общий
  переключатель работы, **общее расписание** (интервал/окна/повтор/старт).
- **Устройство**: имя, активность, статус привязки, **список платежей**
  (реквизиты + сумма + «несколько платежей» + количество).

## Мгновенная доставка (long-poll)
Каждое устройство держит один открытый `POST /api/device/sync`. Сервер отпускает
его в момент изменения (тумблер работы, активность устройства, платежи,
расписание, отзыв токена). `bumpToken`/`bumpDevice` будят припаркованные запросы.

## API (кратко)
**Устройство:** `POST /api/device/pair`, `POST /api/device/sync`
(config содержит `number`, расписание и `payments` с уже вычисленным `count`).

**Мини-апп** (заголовок `X-Init-Data`): `GET /api/mini/state`,
`POST /api/mini/bind`, `POST /api/mini/global`, `POST /api/mini/schedule`,
`POST /api/mini/device`, `POST /api/mini/device/:id/{payments,active,name,pair}`,
`DELETE /api/mini/device/:id`.

**Админ** (initData, только `ADMIN_TG_ID`): `GET /api/admin/tokens`,
`POST /api/admin/token`, `POST /api/admin/token/:id/{quota,extend,toggle,unbind}`,
`DELETE /api/admin/token/:id`.

**Резервная админка:** `GET /admin` (Basic-auth).

## Развёртывание
Файлы в [`deploy/`](deploy/): `alfa-sms.service` (systemd) и
`nginx-project.alfa-vpn.ru.conf` (отдельный поддомен, `proxy_read_timeout 120s` для
long-poll). Каталог `data/` в `.gitignore` — делайте бэкап.
