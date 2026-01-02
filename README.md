# ☁️ Photogram Backup

![Build Status](https://img.shields.io/github/actions/workflow/status/jaheen12/photogram-backup/build.yml)
![Platform](https://img.shields.io/badge/Platform-Android-green)
![License](https://img.shields.io/badge/License-MIT-blue)

**Photogram** is a modern, professional-grade Android application that turns **Telegram** into your unlimited, secure, and folder-organized cloud storage.

> **Note:** This app uses the Telegram Bot API to upload photos. Each folder on your phone becomes a distinct **Topic** in your private Telegram Group.

---

## ✨ Key Features

### 🚀 **Next-Gen Performance**
*   **Smart Delta Scanner:** Uses Android `MediaStore` to find *only* new photos instantly. Zero battery drain from full-disk scanning.
*   **Resilient Background Engine:** Powered by `WorkManager`. Handles network drops, battery optimizations, and system reboots automatically.
*   **Flood Control:** Intelligent upload throttling (3s delay) to prevent Telegram API bans.

### 🧠 **Cloud Brain Memory**
*   **Reinstall-Proof:** The app stores its upload history (`history.json`) and Topic Registry inside your Telegram Group.
*   **Auto-Restore:** If you uninstall the app or switch phones, Photogram downloads its memory from the cloud and resumes exactly where it left off. No duplicates.

### 🎨 **2026 Modern UI**
*   **Glossy Dashboard:** Real-time progress monitoring with a glassmorphism aesthetic.
*   **Floating Search:** Instantly filter through hundreds of folders.
*   **Real-Time Feedback:** Watch filenames and progress bars update live as photos sync.

### 🔒 **Hybrid Security**
*   **Plug-and-Play:** Comes with an embedded Official Bot for instant setup.
*   **Power User Mode:** Option to input your own **Custom Bot Token** for complete privacy and rate-limit isolation.
*   **Encrypted Builds:** Official tokens are injected via GitHub Secrets and stripped from the source code.

---

## 🛠️ Setup Guide

1.  **Create a Telegram Group:**
    *   Create a new Group in Telegram.
    *   **Enable Topics** in the Group Settings (Essential!).
2.  **Get the App:**
    *   Download the latest APK from the [Releases](https://github.com/jaheen12/photogram-backup/actions) page.
3.  **Configure:**
    *   Open **Settings**.
    *   Enter your **Group Chat ID** (e.g., `-100123456789`).
    *   *(Optional)* Enter your own Bot Token if you want to use a private bot.
4.  **Sync:**
    *   Go to the Dashboard.
    *   Toggle **ON** the folders you want to backup.
    *   Click **Sync Now**.

---

## 🏗️ Building from Source

This project uses **GitHub Actions** for CI/CD.

### Prerequisites
*   Android SDK 34
*   Java JDK 17
*   Gradle 8.2

### Environment Variables
To build this securely, you must set the following **Repository Secret** in GitHub:
*   `TELEGRAM_BOT_TOKEN`: Your default fallback bot token.

### Local Build
```bash
git clone https://github.com/jaheen12/photogram-backup.git
cd photogram-backup
gradle assembleDebug

🔧 Tech Stack
Language: Java
Architecture: MVVM-Lite (Activity + Worker + Helper)
Networking: OkHttp 4 + Multipart Uploads
Database: SQLite (Custom indexed implementation)
Scheduling: AndroidX WorkManager
UI: XML Layouts with Material Design principles
