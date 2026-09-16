# 🛡️ BOSS Agent Guardrail & Policy Interceptor Plugin

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Desktop-1.6.11-teal.svg?logo=jetpackcompose)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![Build Status](https://img.shields.io/badge/Tests-140%2B%20Passed-brightgreen.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

An open-source, native Kotlin safety middleware plugin for **[BOSS Console](https://github.com/risa-labs-inc/BossConsole)**.

It intercepts dangerous shell commands and destructive agent actions before they reach the host environment, providing human operators with an interactive real-time Compose Desktop approval sheet, session whitelisting, and a full security audit trail.

---

## 🚀 Key Features

* **⚡ Non-Blocking Coroutine Interception:** Uses Kotlin Coroutines (`CompletableDeferred`) to suspend agent tool execution without blocking the AWT/Compose Desktop Event Dispatch Thread (EDT).
* **🖥️ Native Compose Desktop Approval Modal:**
  * Real-time risk breakdown and triggered security policy explanations.
  * Linear progress bar countdown (60s) with auto-deny fallback.
  * Keyboard shortcuts: **Enter** (Allow Once), **Escape** (Deny).
  * **"Allow Once"**, **"Allow for Session"**, and **"Deny"** controls.
* **🛡️ 11 Security Protection Tiers:**
  1. **Filesystem Destruction:** `rm -rf /`, `rm -r node_modules`, `shred`, `xargs rm`
  2. **Disk & Partition Formatting:** `dd of=/dev/sda`, `mkfs.ext4`, `fdisk`, `parted`
  3. **Git Destructive Actions:** `git reset --hard`, `git clean -fdx`, `git push --force`
  4. **Permission & Escalation:** `chmod 777`, `sudo su`, `chown -R root:root`
  5. **System Kill & DoS:** `shutdown`, `reboot`, `killall -9`, Fork bombs
  6. **Remote Script Pipes:** `curl ... | bash`, `wget ... | sh`, `base64 -d | bash`
  7. **Secret & Key Tampering:** `.env`, `id_rsa`, `.aws/credentials`, `/etc/shadow`
  8. **Database Destructive:** `DROP DATABASE`, `TRUNCATE TABLE`, `DELETE FROM users;` (no WHERE)
  9. **Container & Orchestration:** `docker system prune -a`, `kubectl delete ns`
  10. **Network Security:** `iptables -F`, `ufw disable`
  11. **Service Management:** `systemctl stop docker`
* **⚙️ Custom JSON Rules:** Easily define custom regex-based detection rules in `~/.boss/plugins/config/guardrail-rules.json`.
* **📋 Interactive Dashboard:** 
  * Live metrics, toggle interceptor on/off.
  * Searchable/filterable Security Audit Log.
  * Reference list of all active policies.
  * Interactive Sandbox Tester with command history.

---

## 🏗️ Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                        BOSS Console Workspace                          │
│                                                                        │
│   ┌───────────────┐               ┌────────────────────────────────┐   │
│   │   AI Agent    │ ──ToolCall──> │    GuardrailInterceptor        │   │
│   │ (Claude/Codex)│               │     (Kotlin Coroutines)        │   │
│   └───────────────┘               └───────────────┬────────────────┘   │
│                                                   │                    │
│                         ┌─────────────────────────┴────────────────┐   │
│                         ▼                                          ▼   │
│                  [Safe Commands]                           [Dangerous] │
│                         │                                          │   │
│                         │                            `CompletableDeferred`
│                         │                                          │   │
│                         │                           ┌──────────────▼─┐ │
│                         │                           │   Compose UI   │ │
│                         │                           │ Approval Sheet │ │
│                         │                           └──────────────┬─┘ │
│                         │                                          │   │
│                         ▼ <──────── (User: Approve / Session) ─────┘   │
│              ┌──────────────────────┐                                  │
│              │ Native Terminal / FS │                                  │
│              └──────────────────────┘                                  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 🧪 Building & Running

Run the full automated test suite (140+ tests):
```bash
./gradlew test --no-daemon
```

Run the standalone Compose Desktop app to test the UI:
```bash
./gradlew run --no-daemon
```

Build the production plugin JAR:
```bash
./gradlew buildPluginJar --no-daemon
```

---

## 🛠️ Installation in BOSS

### Option 1: Manual Installation
1. Quit BOSS Console.
2. Copy the built JAR:
   ```bash
   mkdir -p ~/.boss/plugins
   cp build/libs/boss-guardrail-plugin-1.0.0.jar ~/.boss/plugins/
   ```
3. Launch BOSS Console and open **Toolbox** to verify the plugin is active.

### Option 2: Hot Reload with Tool Evolver
```json
{
  "plugin_id": "ai.boss.guardrail",
  "jar_path": "/absolute/path/to/boss-guardrail-plugin/build/libs/boss-guardrail-plugin-1.0.0.jar"
}
```

---

## 📄 License
Apache License 2.0.
