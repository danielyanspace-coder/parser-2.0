# Готовые сборки APK (для OTA)

Здесь лежат подписанные релизные APK основного приложения ALFA SMS, чтобы их
можно было выложить на сервере **без пересборки** — просто `git pull` и загрузка
в OTA.

| Файл | versionCode | versionName |
|------|-------------|-------------|
| `alfa-sms-v2.8.apk` | 9 | 2.8 |
| `alfa-sms-v2.7.apk` | 8 | 2.7 |
| `alfa-sms-v2.6.apk` | 7 | 2.6 |
| `alfa-sms-v2.5.apk` | 6 | 2.5 |
| `alfa-sms-v2.4.apk` | 5 | 2.4 |
| `alfa-sms-v2.2.apk` | 4 | 2.2 |

## Как выложить обновление (на сервере)
```bash
cd /path/to/parser-2.0 && git pull && sudo systemctl restart alfa-sms

# Залить APK и объявить версию (подставь свой ADMIN_PASSWORD):
curl -u admin:ВАШ_ADMIN_PASSWORD -X PUT --data-binary @releases/alfa-sms-v2.8.apk \
  https://project.alfa-vpn.ru/admin/apk
curl -u admin:ВАШ_ADMIN_PASSWORD -X POST -H "Content-Type: application/json" \
  -d '{"versionCode":9,"versionName":"2.8","notes":"Новая система мониторинга сигналов с большей конверсией"}' \
  https://project.alfa-vpn.ru/admin/release
```
После этого пользователям при открытии приложения предложит «Обновить».

> Подпись — тем же ключом (`app/alfa-release.jks`), пакет `com.checkout.alfasms`,
> поэтому ставится поверх без удаления. `versionCode` всегда должен расти.
