# LocationUpdate - Wear OS & Android Companion Workout Tracker

LocationUpdate is an Android solution consisting of a **Wear OS watch app** (`:app`) and an **Android phone companion app** (`:phone`) designed to record walking, hiking, and cycling workouts. It tracks GPS location every 15 seconds, optimizes battery life using hardware-level sensor batching and Bluetooth LE offloading, and posts structured lifecycle events and location points directly to a backend `receiver.php` script.

---

## Architecture & Features

### 1. Wear OS Watch App (`:app`)
* **Foreground Service (`WorkoutForegroundService`)**: Runs continuously in the background for the duration of the workout without shutting down (`foregroundServiceType = "location"`).
* **Direct HTTP Lifecycle Commands**: Uses **OkHttp** to send critical workout events (`workout_started`, `workout_paused`, `workout_resumed`, `workout_ended`) directly to `receiver.php`. Upon successful server confirmation, the watch triggers a **haptic wrist vibration** and updates its display.
* **Low-Power BLE Streaming**: Streams batched GPS location points (`location_batch`) over Bluetooth Low Energy (BLE) using Google Play Services `MessageClient` to offload network activity to the phone companion app.
* **Jetpack Compose Material 3 UI**:
  * **Start / Pause / Resume / End** controls with round, side-by-side buttons.
  * **Starting Modal**: Shows a loading spinner with **Retry** and **Abort** options while waiting for server confirmation.
  * **Live Color-Coded Status**: **Green** for active tracking, **Amber** when paused.
  * **Smart Screen Wake**: Automatically keeps the watch screen awake for 20 seconds upon interaction before allowing normal sleep/dimming.

### 2. Android Phone Companion App (`:phone`)
* **`WatchBridgeService` (`WearableListenerService`)**: Listens for incoming BLE messages from the watch, unpacks them, and POSTs location batches to `receiver.php`.
* **Server URL Auto-Sync**: Automatically syncs the configured `receiver.php` URL over Bluetooth to the watch (`/ride/set_url`, `/ride/get_url`).
* **Diagnostics Dashboard**: View live sync status, last received messages, server upload responses, and test server connectivity.

### 3. Backend (`receiver.php`)
* Processes incoming JSON events (`workout_started`, `workout_paused`, `workout_resumed`, `location_update`, `workout_ended`) and persists them securely into MySQL tables (`WORKOUTS`, `WORKOUT_POINTS`) using PDO.

---

## Project Structure

```text
LocationUpdate/
├── app/                  # Wear OS Watch Module
│   └── src/main/java/com/sk7software/locationupdate/
│       ├── presentation/ # Jetpack Compose UI (MainActivity.kt)
│       └── service/      # Foreground service & BLE data listeners
├── phone/                # Android Phone Companion Module
│   └── src/main/java/com/sk7software/locationupdate/phone/
│       ├── PhoneMainActivity.kt  # URL configuration & diagnostics
│       └── WatchBridgeService.kt # BLE listener & HTTP upload bridge
└── README.md
```

---

## Getting Started & Setup

1. **Configure Backend**: Ensure your `receiver.php` and `config.php` are deployed to your server and connected to your MySQL database.
2. **Build & Deploy**:
   * Open the project in Android Studio.
   * Deploy the `:phone` module to your Android phone.
   * Open the phone app, enter your `receiver.php` URL, and tap **Save URL** (which automatically syncs it to the watch).
   * Deploy the `:app` module to your Wear OS watch.
3. **Usage**:
   * Open the watch app and tap **Start**.
   * Wait for the **"Server Confirmed"** haptic vibration and green indicator.
   * Use **Pause**, **Resume**, and **End** as needed during your workout.
