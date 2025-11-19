# 🤝 Contributing to AIO Battery Monitor

First off, thank you for considering contributing to AIO Battery Monitor!

This project is a blend of human creativity and AI efficiency. We welcome all types of contributions—bug fixes, feature enhancements, documentation improvements, and UI polishes.

---

## 📋 Prerequisites

Before you begin, ensure you have the following installed:
1.  **Android Studio:** Ladybug (2024.2.1) or newer recommended.
2.  **JDK:** Java 11 or 17 (Project uses `JavaVersion.VERSION_11`).
3.  **Git:** For version control.

---

## 🚀 How to Replicate & Build

Since this project relies on local resources for privacy (no URL fetching), there is one manual step required after cloning.

1.  **Clone the Repository**
    ```bash
    git clone https://github.com/prvn2004/batteryreminder.git
    cd batteryreminder
    ```

2.  **Open in Android Studio**
    *   Open Android Studio -> File -> Open -> Select the project folder.
    *   Let Gradle sync.

    *You can use any PNGs you like, or the vector XMLs provided in the source code will act as fallbacks if they exist.*

3**Build & Run**
    *   Connect a physical Android device (Emulators cannot accurately test Battery Current/Voltage).
    *   Run the `app` configuration.

---

## 📐 Coding Standards

We follow a strict **"Mind First"** approach.
*   **Modular Code:** Do not write spaghetti code. Use `Extensions`, `Utils`, and separate `ViewModel` logic.
*   **Architecture:** Stick to MVVM.
    *   Logic goes in `ViewModel` or `UseCases/Engines`.
    *   UI goes in `Fragments/Activities`.
*   **Privacy Check:** **NEVER** add the `INTERNET` permission to `AndroidManifest.xml`. If a feature requires internet, it will be rejected.
*   **AI Usage:** You are free to use AI (ChatGPT, Claude, Copilot) to generate code, **BUT** you must verify it. You are responsible for the code you commit.

---

## 🔍 How to Contribute

### 1. Reporting Bugs
*   Go to the [Issues](https://github.com/prvn2004/batteryreminder/issues) tab.
*   Click **New Issue**.
*   Describe the bug, your device model, and Android version.

### 2. Submitting Changes
1.  **Fork** the repository.
2.  Create a new **Branch** for your feature:
    ```bash
    git checkout -b feature/amazing-new-feature
    ```
3.  **Commit** your changes with meaningful messages.
    ```bash
    git commit -m "feat: Added OLED burn-in protection mode"
    ```
4.  **Push** to your branch.
    ```bash
    git push origin feature/amazing-new-feature
    ```
5.  Open a **Pull Request** (PR).
    *   Explain what you changed.
    *   Explain *why* you changed it.
    *   If it's a UI change, please attach a screenshot.

---

## 🧪 Testing Requirements

*   **Cable Benchmark:** Must be tested on a physical device while charging.
*   **Alerts:** Test overlay permissions on API 24 (Nougat) through API 35 (Android 15).
*   **Background Service:** Ensure the `ForegroundService` notification appears on boot.

---

## 📜 License By Contributing
By contributing your code, you agree that your contributions will be licensed under the same license as the project (MIT/Open Source).