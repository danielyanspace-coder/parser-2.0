# Готовые сборки APK (для OTA)

Здесь лежат подписанные релизные APK основного приложения ALFA SMS, чтобы их
можно было выложить на сервере **без пересборки** — просто `git pull` и загрузка
в OTA.

| Файл | versionCode | versionName |
|------|-------------|-------------|
| `alfa-sms-v2.2.apk` | 4 | 2.2 |

## Как выложить обновление (на сервере)
```bash
cd /path/to/parser-2.0 && git pull && sudo systemctl restart alfa-sms

# Залить APK и объявить версию (подставь свой ADMIN_PASSWORD):
curl -u admin:ВАШ_ADMIN_PASSWORD -X PUT --data-binary @releases/alfa-sms-v2.2.apk \
  https://project.alfa-vpn.ru/admin/apk
curl -u admin:ВАШ_ADMIN_PASSWORD -X POST -H "Content-Type: application/json" \
  -d '{"versionCode":4,"versionName":"2.2","notes":"Стабильная работа в фоне и без слёта привязки"}' \
  https://project.alfa-vpn.ru/admin/release
```
После этого пользователям при открытии приложения предложит «Обновить».

> Подпись — тем же ключом (`app/alfa-release.jks`), пакет `com.checkout.alfasms`,
> поэтому ставится поверх без удаления. `versionCode` всегда должен расти.
