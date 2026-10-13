# Triki Music Controller for Android 📱🎵

Мобильное Android-приложение для управления воспроизведением музыки с помощью умного носимого BLE-контроллера **Triki** (кольцо/шайба).

![Platform](https://img.shields.io/badge/Platform-Android-green.svg)
![Language](https://img.shields.io/badge/Language-Java%20%2F%20Kotlin-orange.svg)
![Min SDK](https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-brightgreen.svg)
![Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-blue.svg)
![BLE](https://img.shields.io/badge/Bluetooth-LE%205.0-0082FC.svg)
![Localization](https://img.shields.io/badge/Languages-%F0%9F%87%B7%F0%9F%87%BA%20RU%20%7C%20%F0%9F%87%AC%F0%9F%87%A7%20EN%20%7C%20%F0%9F%87%B5%F0%9F%87%B1%20PL-brightgreen.svg)

---

## 🌟 Основные возможности

1. **Беспроводное BLE-подключение (Bluetooth Low Energy)**:
   - Быстрый сканер и автосопряжение с кольцом Triki.
   - Считывание и вывод актуального уровня заряда батареи (Battery Level %).
   - Обработка разрывов связи и автоматическое переподключение.

2. **Согласованное с ПК ядро распознавания жестов**:
   - Вшитые профили жестов (`assets/gestures`), обученные и откалиброванные в ПК-студии.
   - Распознавание взмахов вниз, влево, вправо, вращений и отсечение шума руки (`None`).
   - Фильтрация по удержанию физической кнопки Triki.

3. **📊 Живая телеметрия сенсоров**:
   - Акселерометр (X, Y, Z в G).
   - Гироскоп (X, Y, Z в °/с).
   - Визуальные индикаторы ускорения и угловой скорости в реальном времени.

4. **Фоновая служба (Foreground Service)**:
   - Бесперебойная работа с выключенным экраном или поверх других приложений (Spotify, YouTube Music, Яндекс Музыка).
   - Кастомная монохромная иконка в строке состояния (`ic_stat_triki`), оптимизированная под Android и экраны смартфонов (включая POCO / Xiaomi MIUI / HyperOS).
   - Постоянное сервисное уведомление с отображением статуса подключения и заряда батареи.

5. **Адаптивный современный UI**:
   - Поддержка вырезов экранов (Display Cutout / Notch / Punch-hole) — элементы управления и статус-бар не накладываются на часы и камеру.
   - Темная тема с неоновыми карточками и плавной анимацией.

6. **🌍 Трёхъязычная локализация**:
   - 🇷🇺 Русский (`values-ru`)
   - 🇬🇧 Английский (по умолчанию `values`)
   - 🇵🇱 Польский (`values-pl`)
   - Язык автоматически адаптируется под системный язык устройства.

---

## 🛠 Сборка и запуск

### Требования
- Android Studio Iguana / Jellyfish или IntelliJ IDEA 2023.3+
- JDK 17 или новее
- Android SDK (API Level 26–34)

### Сборка через Gradle
Склонируйте репозиторий:
```bash
git clone https://github.com/your-username/TrikiMusicController-Android.git
cd TrikiMusicController-Android
```

Сборка Debug APK:
```bash
./gradlew assembleDebug
```
Готовый файл APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

Сборка Release APK / App Bundle:
```bash
./gradlew assembleRelease
# или
./gradlew bundleRelease
```

---

## 📂 Структура проекта

```text
TrikiMusicController-Android/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml              # Разрешения Bluetooth, Foreground Service, активность
│   │   ├── assets/gestures/                 # Предварительно обученные профили движений Triki
│   │   ├── java/com/triki/musiccontroller/
│   │   │   ├── MainActivity.java            # Главный интерфейс: телеметрия, карточки жестов, настройки
│   │   │   ├── TrikiApp.java                # Инициализация каналов уведомлений приложения
│   │   │   ├── ble/TrikiBleManager.java     # Поиск BLE, подключение, парсер пакетов IMU
│   │   │   ├── engine/GestureEngine.java    # Алгоритмы DTW и классификатор жестов
│   │   │   ├── media/MediaController.java   # Эмуляция KeyEvent мультимедиа на Android
│   │   │   ├── model/                       # Модели данных: ImuSample, GestureProfile, MusicAction
│   │   │   └── service/TrikiForegroundService.java # Фоновый сервис с уведомлением
│   │   └── res/
│   │       ├── drawable-*/ic_stat_triki.png # Иконка статус-бара (mdpi -> xxxhdpi)
│   │       ├── layout/activity_main.xml     # Разметка интерфейса
│   │       ├── values/strings.xml           # Английская локализация (default)
│   │       ├── values-ru/strings.xml        # Русская локализация
│   │       └── values-pl/strings.xml        # Польская локализация
│   └── build.gradle                         # Зависимости и конфигурация модуля app
├── gradle/wrapper/                          # Gradle Wrapper
├── build.gradle                             # Корневой Gradle-скрипт
├── settings.gradle                          # Настройки проекта
└── gradle.properties                        # Параметры JVM и сборки
```

---

## 📄 Лицензия

MIT License. Свободно для личного и коммерческого использования.
