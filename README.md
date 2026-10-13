# Triki Music Controller for Android 📱🎵

[🇬🇧 English](#-english) | [🇵🇱 Polski](#-polski)

---

# 🇬🇧 English

> ⚠️ **LEGAL DISCLAIMER & NOTICE**:  
> This project is an **independent, non-commercial hobby experiment and DIY educational research project** created solely for personal learning, reverse engineering practice, and exploration of Bluetooth Low Energy (BLE) protocols and 6-axis Inertial Measurement Unit (IMU) gesture processing on Android.  
> **This project is NOT affiliated, associated, authorized, endorsed by, or in any way officially connected with Żabka Polska sp. z o.o.**, its subsidiaries, or its affiliates. The names "Triki", "Żabka", "Żappka", and related marks or logos are registered trademarks of their respective owners. Any reference to these names is strictly for hardware compatibility identification and educational purposes under **Nominative Fair Use**. This repository does not contain, reverse engineer, or distribute any proprietary code, cloud endpoints, or confidential keys.

---

![Platform](https://img.shields.io/badge/Platform-Android-green.svg)
![Language](https://img.shields.io/badge/Language-Java%20%2F%20Kotlin-orange.svg)
![Min SDK](https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-brightgreen.svg)
![Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-blue.svg)
![BLE](https://img.shields.io/badge/Bluetooth-LE%205.0%20(GATT)-0082FC.svg)
![License](https://img.shields.io/badge/License-MIT-green.svg)

**Triki Music Controller for Android** is a mobile application that turns the **Triki** wearable Bluetooth ring/puck into a wireless gesture controller for mobile media playback while running seamlessly in the background.

---

## 🔍 How the Triki Hardware & Communication Works

The **Triki** device is a miniature wearable gadget equipped with:
1. **Bluetooth Low Energy (BLE 5.0)** transceiver (Nordic Semiconductor architecture).
2. **6-Axis Motion Sensor (IMU)**: 3-axis accelerometer and 3-axis gyroscope.
3. **Tactile Push Button**: Physical click / hold detection.
4. **Rechargeable Li-Po Battery**: Built-in charge and voltage monitoring.

### 📡 BLE GATT Architecture
The app communicates with the device via standard BLE services:
* **Nordic UART Service (NUS)**:
  * Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
  * RX (Write command): `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
  * TX (Notify telemetry): `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
* **Battery Service (`0x180F`)**:
  * Battery Level (`0x2A19`): Reads current battery percentage (0–100%).

### 📦 Packet Structure & Protocol
1. **Stream Activation**: The app initiates streaming by sending an 8-byte command to the RX characteristic:  
   `0x20 0x10 0x00 0xD0 0x07 0x68 0x00 0x03`
2. **Incoming 14-Byte Telemetry Frame**:
   * **Byte 0**: Header `0x22`
   * **Byte 1**: Button State (`0x00` = released, `0x01` = pressed/held)
   * **Bytes 2–7**: Accelerometer X, Y, Z (16-bit signed integers, scale: `2048 LSB/G`, ±16G range)
   * **Bytes 8–13**: Gyroscope X, Y, Z (16-bit signed integers, scale: `131 LSB/(°/s)`, ±250 dps range)

---

## 📱 Mobile App Features

1. **Button-Gated Gesture Classification**:
   * Pre-trained gesture profiles (`app/src/main/assets/gestures`) calibrated in the PC studio: `NextTrack`, `PreviousTrack`, `PlayPause`, `VolumeUp`, `VolumeDown`, `None`/Idle.
   * Recognition runs only while the Triki button is pressed, ensuring **0% false triggers** when walking, running, or moving.
2. **Background Foreground Service**:
   * Runs reliably even with the screen off or while other apps (Spotify, YouTube Music, podcasts) are active.
   * Persistent service notification with live connection status and battery percentage.
   * Tailored monochrome status bar icon (`ic_stat_triki.png`) for Android devices (including POCO, Xiaomi HyperOS, Samsung).
3. **Display Cutout & Notch Safe Insets**:
   * Supports modern edge-to-edge layouts; UI components properly avoid camera punch-holes and status bar clocks.
4. **Standard Media Session Control**:
   * Dispatches system `KeyEvent` / `MediaSession` events (`KEYCODE_MEDIA_PLAY_PAUSE`, `KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_PREVIOUS`).
5. **Localization**:
   * English (`values`), Polish (`values-pl`), Russian (`values-ru`). Automatically matches system language.

---

## 🛠 Build & Install Instructions

### Prerequisites
* Android Studio Iguana / Jellyfish or IntelliJ IDEA.
* JDK 17 or higher.
* Android SDK (API 26–34).

### Build via Command Line
```bash
git clone git@github.com:HaruhakuGIT/TrikiMusicControllerAndroid.git
cd TrikiMusicControllerAndroid
./gradlew assembleDebug
```
The resulting APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---
---

# 🇵🇱 Polski

> ⚠️ **ZASTRZEŻENIE PRAWNE (DISCLAIMER)**:  
> Niniejszy projekt jest **niezależnym, niekomercyjnym eksperymentem hobbystycznym oraz projektem edukacyjno-badawczym (DIY / Reverse Engineering)**, stworzonym wyłącznie do celów naukowych w zakresie protokołów Bluetooth Low Energy (BLE) oraz algorytmów rozpoznawania gestów z 6-osiowych czujników inercyjnych (IMU) na platformie Android.  
> **Projekt NIE jest w żaden sposób powiązany, autoryzowany, sponsorowany ani powiązany z firmą Żabka Polska sp. z o.o.** ani jej podmiotami zależnymi. Nazwy „Triki”, „Żabka”, „Żappka” oraz powiązane znaki towarowe należą do ich prawnych właścicieli i zostały użyte wyłącznie w celach identyfikacji kompatybilności sprzętowej w ramach **dozwolonego użytku (Nominative Fair Use)**. Repozytorium nie zawiera ani nie dystrybuuje żadnego prawnie zastrzeżonego kodu, usług chmurowych ani kluczy dostępowych.

---

**Triki Music Controller for Android** to aplikacja mobilna, która przekształca urządzenie ubieralne **Triki** (BLE) w bezprzewodowy kontroler muzyki działający niezawodnie w tle na telefonie.

---

## 🔍 Budowa i komunikacja urządzenia Triki

Urządzenie **Triki** to miniaturowy kontroler wyposażony w:
1. **Moduł Bluetooth Low Energy (BLE 5.0)** (architektura Nordic Semiconductor).
2. **6-osiowy czujnik IMU**: Akcelerometr 3D + żyroskop 3D.
3. **Przycisk fizyczny**: Rejestracja wciśnięcia / przytrzymania.
4. **Akumulator Li-Po** z monitoringiem napięcia.

### 📡 Usługi BLE GATT
* **Nordic UART Service (NUS)**:
  * UUID Usługi: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
  * RX (Wysyłanie komend): `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
  * TX (Odbiór telemetrii): `6e400003-b5a3-f393-e0a9-e50e24dcca9e`
* **Battery Service (`0x180F`)**:
  * Poziom baterii (`0x2A19`): Odczyt stanu naładowania (0–100%).

### 📦 Format ramek danych
1. **Start transmisji**: Wysłanie 8 bajtów do RX:  
   `0x20 0x10 0x00 0xD0 0x07 0x68 0x00 0x03`
2. **14-bajtowa ramka danych**:
   * **Bajt 0**: Nagłówek `0x22`
   * **Bajt 1**: Stan przycisku (`0x00` = puszczony, `0x01` = wciśnięty)
   * **Bajty 2–7**: Akcelerometr X, Y, Z (int16, skala 2048 LSB/G)
   * **Bajty 8–13**: Żyroskop X, Y, Z (int16, skala 131 LSB/(°/s))

---

## 📱 Funkcje aplikacji mobilnej

1. **Rozpoznawanie gestów z blokadą przyciskiem**:
   * Prekalibrowane profile ruchów (`assets/gestures`): Machnięcie w dół (Pauza/Play), w prawo (Dalej), w lewo (Wstecz), obroty (Głośność) oraz szum dłoni.
   * Gest jest przetwarzany wyłącznie przy wciśniętym przycisku, co eliminuje **0% fałszywych kliknięć** w ruchu.
2. **Usługa w tle (Foreground Service)**:
   * Kontroler działa bez zakłóceń przy wyłączonym ekranie oraz w trakcie korzystania z innych aplikacji muzycznych (Spotify, YouTube Music).
   * Dedykowana ikona na pasku stanu (`ic_stat_triki.png`) dopasowana do Androida (w tym nakładek POCO/Xiaomi MIUI/HyperOS).
   * Ciągłe powiadomienie informujące o stanie połączenia i poziomie naładowania baterii Triki.
3. **Bezpieczne marginesy dla wcięć ekranu (Display Cutout / Notch)**:
   * Górny pasek aplikacji nie nachodzi na wycięcie aparatu i zegar systemowy.
4. **Sterowanie dźwiękiem Android**:
   * Standardowe zdarzenia `KeyEvent` / `MediaSession`.
5. **Wielojęzyczność**:
   * Pełne wsparcie dla języka polskiego (`values-pl`), angielskiego (`values`) oraz rosyjskiego (`values-ru`).

---

## 🛠 Kompilacja i instalacja

### Wymagania:
* Android Studio lub IntelliJ IDEA.
* JDK 17 lub nowszy.
* Android SDK (API 26–34).

### Kompilacja z konsoli:
```bash
git clone git@github.com:HaruhakuGIT/TrikiMusicControllerAndroid.git
cd TrikiMusicControllerAndroid
./gradlew assembleDebug
```
Gotowy plik APK znajduje się w katalogu:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 📄 Licencja

MIT License — dozwolone do bezpłatnego użytku prywatnego i edukacyjnego.
