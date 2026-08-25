# 🌸 Recora — запис екрана для Android

---

[![Made with Arena.ai Agent](./arena-agent.svg)](https://arena.ai/agent)

![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white) [![Licence](https://img.shields.io/github/license/Ileriayo/markdown-badges?style=for-the-badge)](./LICENSE.md)

![Android](https://img.shields.io/badge/Android-%233DDC84.svg?style=for-the-badge&logo=android&logoColor=white)

---

Простий застосунок для запису екрана на **Android 7.0/7.1 (API 24) і новіших**, написаний на Kotlin.
Запис виконується через MediaProjection API, відео зберігається у форматі MP4 (H.264).

> ℹ️ **Інфо:** Цей проєкт все ще `розробляється` і **може містити помилки!**
> 
> Також Додаток містить код написаним **ШІ**, якщо вам неподобаються `"Слоп-Додатки"` створені Штучним інтелектом: вам необов'язково завантажувати додаток! (те саме якщо друг не любить **Додатки створенні ШІ**)
>
> Що до хороших новин.. Цей додаток є **Open Source** і я як автор цього проєкту, дозволяю використовувати та модифікувати весь код додатку!
>
> Також можна видавати його за свій додаток але можна залишити Кредити оригіналу.. (Майте повагу..)

## Можливості (v0.1)

- ⏺ Запис екрана у MP4 (до 1280p, 30 fps, H.264)
- 🔔 Фоновий сервіс зі сповіщенням та кнопкою «Зупинити»
- 📼 Список записів у застосунку, перегляд по тапу
- 🖼 Автоматична поява відео у «Галереї» (MediaScanner)
- 🔒 Без зайвих дозволів на сховище: записи у власній теці застосунку
  (`Android/data/com.recora.app/files/Movies/ScreenRecorder`)

> ⚠️ У v0.1 записи прив'язані до теки застосунку і **видаляються разом з ним**.
> Перенесення у спільну теку «Фільми» через MediaStore заплановано у v0.2.

## Вимоги

- Android Studio Ladybug або новіша (або лише JDK 17+ для збірки з консолі)
- Android SDK 35 (підтягнеться автоматично в Android Studio)

## Збірка APK

### Локально (консоль)

```bash
bash gradlew assembleDebug
```

Готовий файл: `app/build/outputs/apk/debug/app-debug.apk`

### Android Studio

1. **File → Open** → оберіть теку проєкту
2. Дочекайтеся синхронізації Gradle
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**

### GitHub Actions 🤖

Workflow уже налаштовано (`.github/workflows/android.yml`): кожен `push` у гілку
`main`/`master` автоматично збирає Debug APK.

Готовий APK забирайте тут:
**GitHub → Actions → оберіть останній запуск → Artifacts → `recora-debug-apk`**

## Публікація на GitHub

```bash
git remote add origin https://github.com/<ваш-нік>/<репозиторій>.git
git push -u origin main
```

## Структура проєкту

```
screen-recorder/
├── .github/workflows/android.yml   # CI: автозбірка APK на GitHub
├── app/
│   ├── build.gradle.kts            # конфігурація модуля (minSdk 24, target 35)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/screenrecorder/app/
│       │   ├── MainActivity.kt         # UI + запит дозволу MediaProjection
│       │   ├── ScreenRecordService.kt  # запис екрана (MediaProjection + MediaRecorder)
│       │   └── RecordingsAdapter.kt    # список відео
│       └── res/                    # макети, рядки (укр.), іконки, тема
├── build.gradle.kts                # версії AGP 8.7.3 / Kotlin 2.0.21
└── settings.gradle.kts
```

## Як це працює технічно

`MediaProjection` створює `VirtualDisplay`, який «малює» копію екрана на
`Surface` від `MediaRecorder`. Сервіс працює у foreground з типом
`mediaProjection` (вимога Android 14+). Після зупинки файл сканується
MediaScanner'ом і з'являється у «Галереї».

## Дорожня карта 🗺

| Версія | Що додамо |
|--------|-----------|
| v0.2 | 🎤 Звук мікрофона, збереження у спільну теку «Фільми» (MediaStore) |
| v0.3 | ⚙️ Налаштування: роздільна здатність, бітрейт, FPS |
| v0.4 | ⏸ Пауза / відновлення запису (працює з Android 7.0) |
| v0.5 | 🎈 Плаваюча кнопка поверх інших застосунків |
| v0.6 | 🔊 Внутрішній звук пристрою (потребує Android 10+) |
| v0.7 | ⏱ Лічильник часу, зворотний відлік перед стартом |

## Ліцензія

[MIT](LICENSE)
