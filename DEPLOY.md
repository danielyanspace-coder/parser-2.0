# Установка ALFA SMS 2.0 — по шагам

Инструкция максимально подробная. Делайте строго по порядку. Ничего из
существующего (ваш VPN, старый ALFA SMS) **не трогается** — новый сервис живёт
на отдельном поддомене, отдельном порту и в отдельной папке.

Итог: сервер на `https://project.alfa-vpn.ru`, Telegram-бот с кнопкой мини-аппа,
админка только для вашего Telegram, и APK для телефонов.

> На сервере у вас стоит Claude Code — можно просто дать ему этот файл и сказать
> «сделай по DEPLOY.md». Но ниже всё расписано и для ручного выполнения.

---

## Шаг 0. Что понадобится
- Доступ к серверу по SSH (у вас HELs-2, Ubuntu).
- Домен `alfa-vpn.ru` (есть).
- Телефон с Telegram (для BotFather и проверки).
- Ваш Telegram ID: **8211351879** (уже прописан как админ).

---

## Шаг 1. Поддомен (DNS)
В панели управления доменом `alfa-vpn.ru` добавьте запись:

```
Тип: A
Имя (host): project
Значение: <IP вашего сервера>
```

Должно получиться `project.alfa-vpn.ru`. Подождите 5–15 минут, проверьте:
```bash
ping project.alfa-vpn.ru
```
(должен отвечать IP вашего сервера).

---

## Шаг 2. Node.js (если ещё не стоит)
```bash
node -v   # если >= 18 — пропустите установку
# если команды нет или версия старая:
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs
```

---

## Шаг 3. Скачать проект
```bash
sudo mkdir -p /opt/alfa-sms
sudo chown $USER:$USER /opt/alfa-sms
git clone https://github.com/danielyanspace-coder/parser-2.0 /opt/alfa-sms
cd /opt/alfa-sms/server
```
Зависимостей ставить не нужно — сервер работает на чистом Node.

---

## Шаг 4. Выбрать свободный порт
Старый ALFA SMS и VPN уже что-то слушают. Посмотрите занятые порты:
```bash
sudo ss -ltnp
```
Возьмите **свободный** порт, например `8090` (если занят — 8091 и т.д.).

---

## Шаг 5. Настройки (.env)
```bash
cd /opt/alfa-sms/server
cp .env.example .env
nano .env
```
Заполните:
- `ADMIN_PASSWORD` — придумайте сложный пароль (резервный вход в /admin).
- `ADMIN_TG_ID` — оставьте `8211351879` (это вы).
- `PUBLIC_BASE_URL` — `https://project.alfa-vpn.ru`.
- `PORT` — тот свободный порт из шага 4 (например `8090`).
- `RECIPIENT_NUMBER` — `7878` (номер получателя платёжных SMS).
- `TELEGRAM_BOT_TOKEN` — **пока оставьте пустым**, впишем на шаге 8.

Сохраните (Ctrl+O, Enter, Ctrl+X).

---

## Шаг 6. Автозапуск (systemd)
```bash
sudo cp /opt/alfa-sms/server/deploy/alfa-sms.service /etc/systemd/system/alfa-sms.service
# если клонировали не в /opt/alfa-sms или node не в /usr/bin/node — поправьте пути:
sudo nano /etc/systemd/system/alfa-sms.service    # проверьте WorkingDirectory, EnvironmentFile, ExecStart (which node)

sudo systemctl daemon-reload
sudo systemctl enable --now alfa-sms
sudo systemctl status alfa-sms      # должно быть active (running)
```
Проверка локально:
```bash
curl http://127.0.0.1:8090/health   # ожидаем {"ok":true}
```

---

## Шаг 7. nginx + HTTPS (отдельный поддомен)
```bash
sudo cp /opt/alfa-sms/server/deploy/nginx-project.alfa-vpn.ru.conf /etc/nginx/sites-available/project.alfa-vpn.ru
# если порт не 8090 — поправьте proxy_pass:
sudo nano /etc/nginx/sites-available/project.alfa-vpn.ru

sudo ln -s /etc/nginx/sites-available/project.alfa-vpn.ru /etc/nginx/sites-enabled/
sudo nginx -t          # проверка синтаксиса — должно быть ok
sudo systemctl reload nginx
```
Выпустить сертификат (бесплатный):
```bash
sudo apt-get install -y certbot python3-certbot-nginx
sudo certbot --nginx -d project.alfa-vpn.ru
```
Certbot сам допишет HTTPS в конфиг. Проверьте в браузере:
`https://project.alfa-vpn.ru/health` → `{"ok":true}`.

> Мы добавили **новый** server-блок только для `project.alfa-vpn.ru`. Конфиги вашего
> основного сайта и VPN не изменялись.

---

## Шаг 8. Telegram-бот (BotFather)
1. В Telegram откройте [@BotFather](https://t.me/BotFather) → `/newbot`.
2. Введите имя и username бота (username заканчивается на `bot`).
3. BotFather пришлёт **токен** вида `123456789:AA...`. Скопируйте.
4. Впишите его на сервере:
   ```bash
   nano /opt/alfa-sms/server/.env      # TELEGRAM_BOT_TOKEN=сюда_токен
   sudo systemctl restart alfa-sms
   ```
5. Привяжите мини-апп к боту: BotFather → `/mybots` → выберите бота →
   **Bot Settings → Menu Button → Configure menu button** → введите URL
   `https://project.alfa-vpn.ru/` и текст кнопки (например «Открыть панель»).

Теперь у бота внизу есть кнопка, открывающая мини-апп.

---

## Шаг 9. Проверка админки (это вы)
Откройте своего бота в Telegram, нажмите кнопку меню → откроется мини-апп.
Так как ваш Telegram ID `8211351879` = админ, вверху будет **«⚙ Админ-панель»**.
Зайдите, создайте первый токен: комментарий (кому), срок (дней) и количество
устройств. Токен появится в списке.

> Резервный вход в админку без Telegram: `https://project.alfa-vpn.ru/admin`
> (логин `admin`, пароль из `ADMIN_PASSWORD`).

---

## Шаг 10. Выдать токен пользователю
Дайте пользователю: ссылку на бота + токен из админки.
Пользователь: открывает бота → кнопку меню → вводит токен → он привязывается к
его Telegram (больше вводить не нужно) → задаёт «Настройку интервалов» (общую) →
«Добавить устройство» (имя → QR) → на устройстве настраивает платежи.

---

## Шаг 11. Сборка APK (Android)
APK собирается на машине с Android SDK (ваш ПК с Android Studio или CI).
```bash
git clone https://github.com/danielyanspace-coder/parser-2.0
cd parser-2.0
# создайте local.properties с путём к SDK:
echo "sdk.dir=/путь/к/Android/Sdk" > local.properties
./gradlew assembleRelease
# готовый файл:
# app/build/outputs/apk/release/app-release.apk
```
Раздайте `app-release.apk` пользователям. Приложение — только сканер QR:
установил → открыл → навёл на QR из мини-аппа → устройство привязалось.

> Адрес сервера приложение берёт из QR-кода, поэтому отдельно его в APK
> прописывать не нужно (по умолчанию и так `https://project.alfa-vpn.ru`).

---

## Шаг 12. Финальная проверка (сквозной тест)
1. В админке создайте тестовый токен (1 устройство, 7 дней).
2. В мини-аппе введите токен → «Настройка интервалов» → интервал 15 c, сохраните.
3. «Добавить устройство» → имя → появился QR.
4. На телефоне с APK отсканируйте QR → «Привязано как …».
5. Откройте устройство в мини-аппе → «Добавить платеж» → Реквизиты, Сумма →
   сохраните. При желании включите «несколько платежей…» и укажите количество.
6. Убедитесь, что у устройства зелёный ползунок «Активно».
7. На главном экране включите тумблер **«Начать немедленную работу…»** —
   телефон должен начать отправку **сразу** (в статусе появится «В работе»).
8. Выключите тумблер — отправка сразу останавливается.

Готово.

---

## Выдержит ли сервер нагрузку?
Да, с большим запасом. Прикидка на **6–7 пользователей × 15+ устройств ≈ 100
устройств**:
- Каждое устройство держит **одно** «спящее» HTTP-соединение (long-poll) ~25 c.
  100 простаивающих соединений для Node — это буквально ничего.
- База — JSON-файл на сотни КБ, читается в память.
- **SMS отправляют сами телефоны**, сервер только раздаёт состояние и мгновенно
  будит соединения при переключении тумблера.
- Оперативной памяти нужно десятки МБ; у вас 4 GB и 2 ядра — запас 10–20×.

Единственное требование уже учтено в nginx-конфиге: `proxy_read_timeout 120s`,
чтобы long-poll (25 c) не рвался прокси.

---

## Обновление приложения на телефонах (OTA)
Пользователям НЕ нужно удалять и ставить APK заново — приложение само предложит
обновление. Чтобы выкатить новую версию:

1. Соберите новый APK с увеличенным номером версии:
   ```bash
   ./gradlew assembleRelease -PversionCode=3 -PversionName=2.1
   ```
2. Загрузите его на сервер и объявите версию (пароль — из `ADMIN_PASSWORD`):
   ```bash
   curl -u admin:ВАШ_ПАРОЛЬ -X PUT --data-binary @app/build/outputs/apk/release/app-release.apk \
     https://project.alfa-vpn.ru/admin/apk
   curl -u admin:ВАШ_ПАРОЛЬ -X POST -H 'Content-Type: application/json' \
     -d '{"versionCode":3,"versionName":"2.1","notes":"Что нового в этой версии"}' \
     https://project.alfa-vpn.ru/admin/release
   ```
3. При следующем открытии приложения пользователь увидит окно «Доступно
   обновление» → «Обновить» → установка поверх (номер версии должен быть больше
   установленного, иначе окно не появится).

## Отчёты и «Доработать» в боте
После завершения работы (все устройства отработали платежи, либо вы выключили
общий тумблер) бот **сам** присылает пользователю отчёт: по каждому устройству —
сколько отправлено, какая была задача и сколько не отработано, плюс кнопка
**«Доработать»**. Нажатие → подтверждение → система удаляет исполненные платежи со
всех устройств пользователя, оставляя неисполненные, и просит выставить
расписание в мини-аппе. Webhook бот регистрирует сам при старте сервера (нужен
только `TELEGRAM_BOT_TOKEN` и HTTPS-домен).

## Обслуживание
- Логи: `journalctl -u alfa-sms -f`
- Перезапуск: `sudo systemctl restart alfa-sms`
- Обновление кода сервера:
  ```bash
  cd /opt/alfa-sms && git pull && sudo systemctl restart alfa-sms
  ```
- **Бэкап данных** (токены и устройства): папка `/opt/alfa-sms/server/data/`.
  Делайте её копию; при потере — все привязки и настройки пропадут.
