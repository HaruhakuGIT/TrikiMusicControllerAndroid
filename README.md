# Triki Music Controller for Android 📱🎵

> ⚠️ **DISCLAIMER / ВАЖНОЕ ПРАВОВОЕ УВЕДОМЛЕНИЕ**:  
> Данный проект является **некоммерческим любительским экспериментом и хобби-исследованием** (DIY / Reverse-Engineering / Educational project), созданным исключительно в образовательных целях для изучения протоколов Bluetooth Low Energy (BLE) на платформе Android и интеграции управления мультимедиа с инерциальными датчиками (IMU).  
> **Проект не имеет никакого отношения к компании Żabka Polska sp. z o.o., её дочерним предприятиям или торговым маркам.** Названия «Triki», «Żabka» и сопутствующие обозначения упоминаются исключительно в целях описания совместимости физического оборудования (номинативное добросовестное использование / Nominative Fair Use). Проект не использует и не распространяет закрытый код или сервисы сторонних компаний.

---

![Platform](https://img.shields.io/badge/Platform-Android-green.svg)
![Language](https://img.shields.io/badge/Language-Java%20%2F%20Kotlin-orange.svg)
![Min SDK](https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-brightgreen.svg)
![Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-blue.svg)
![BLE](https://img.shields.io/badge/Bluetooth-LE%205.0%20(GATT)-0082FC.svg)
![Localization](https://img.shields.io/badge/Languages-%F0%9F%87%B7%F0%9F%87%BA%20RU%20%7C%20%F0%9F%87%AC%F0%9F%87%A7%20EN%20%7C%20%F0%9F%87%B5%F0%9F%87%B1%20PL-brightgreen.svg)
![License](https://img.shields.io/badge/License-MIT-green.svg)

**Triki Music Controller for Android** — мобильное приложение для смартфонов на базе Android, позволяющее использовать интерактивное BLE-устройство **Triki** для управления музыкой в фоне во время прогулок, тренировок или поездок.

---

## 🔍 Как устроено и работает устройство Triki

Гаджет **Triki** (носимое кольцо / шайба) оснащен аппаратными компонентами:
1. **Bluetooth Low Energy (BLE 5.0)** трансивер.
2. **6-осевой датчик движения (IMU)**: 3-осевой акселерометр и 3-осевой гироскоп.
3. **Физическая кнопка управления**: для тактильной фиксации намерений пользователя.
4. **Li-Po батарея** с контроллером заряда.

### 📡 Протокол связи (BLE GATT)
Приложение взаимодействует с устройством по протоколу BLE:
* **Сервис потоковой передачи данных (Nordic UART Service)**:
  * Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
  * RX (Write command): `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
  * TX (Notify telemetry): `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
* **Служба информации о батарее (Battery Service 0x180F)**:
  * Характеристика `0x2A19` — считывание текущего процента заряда (0–100%).

### 📦 Структура пакетов
1. **Команда старта**: Для активации передачи телеметрии приложение отправляет в характеристику RX байты:  
   `0x20 0x10 0x00 0xD0 0x07 0x68 0x00 0x03`
2. **Входящий 14-байтовый фрейм**:
   * Байт 0: Заголовок `0x22`
   * Байт 1: Статус нажатия кнопки (`0x00` — отпущена, `0x01` — зажата)
   * Байты 2–7: Ускорение по 3 осям (X, Y, Z int16, масштаб 2048 LSB/G)
   * Байты 8–13: Угловая скорость по 3 осям (X, Y, Z int16, масштаб 131 LSB/(°/с))

---

## 📱 Функции мобильного приложения

1. **Ядро распознавания жестов (Gesture Engine)**:
   * Идентично логике ПК-версии: в папке `app/src/main/assets/gestures` хранятся калиброванные профили жестов (`NextTrack`, `PreviousTrack`, `PlayPause`, `VolumeUp`, `VolumeDown`, `None`/Шум).
   * Распознавание активируется при зажатой кнопке Triki, предотвращая случайные срабатывания при ходьбе или беге.
2. **Фоновая служба (Foreground Service)**:
   * Приложение продолжает обрабатывать жесты и переключать музыку при заблокированном экране телефона или при работе других приложений (Spotify, YouTube Music, Яндекс Музыка).
   * Системное уведомление с динамическим отображением статуса подключения и точного процента заряда батареи Triki.
3. **Оптимизация под экраны и вырезы (Display Cutout / Notch)**:
   * Учтены безопасные отступы (WindowInsets): верхний бар и статус-бар не перекрываются вырезом фронтальной камеры и часами (протестировано на POCO / Xiaomi MIUI/HyperOS, Samsung).
   * Специально подготовленная монохромная иконка строки состояния (`ic_stat_triki.png`), отображающаяся слева в шторке уведомлений Android.
4. **Управление системным звуком и треками**:
   * Через стандартные широковещательные события Android `KeyEvent` и `MediaSession` (`KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_PREVIOUS`).
5. **Трёхъязычная локализация**:
   * 🇷🇺 **Русский** (`values-ru`)
   * 🇬🇧 **English** (по умолчанию `values`)
   * 🇵🇱 **Polski** (`values-pl`)
   * Автоматически подстраивается под язык системы смартфона.

---

## 🛠 Сборка и установка

### Требования:
* Android Studio Iguana / Jellyfish или IntelliJ IDEA.
* JDK 17 или новее.
* Android SDK (API 26–34).

### Сборка через консоль:
```bash
git clone git@github.com:HaruhakuGIT/TrikiMusicControllerAndroid.git
cd TrikiMusicControllerAndroid
./gradlew assembleDebug
```
Готовый файл APK будет находиться по пути:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 Лицензия

MIT License — свободное использование в личных и образовательных целях.
