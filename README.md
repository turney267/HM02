# HM02 — Minimalist Android Email Client Template

**HM02** is a bare-bones, open-source Android email client boilerplate built with **Kotlin**, **Jetpack Compose**, and **Jakarta Mail (IMAP/SMTP)**.

It is designed as a clean foundation for developers who want to build calm, intentional, distraction-free email clients without dealing with complex background sync engines, local SQLite caching of thousands of historical messages, or expensive OAuth/CASA compliance audits.

---

## Core Design Principles

1. **No Notifications & No Background Sync**
   - HM02 declares only `<uses-permission android:name="android.permission.INTERNET" />`.
   - There are no push notifications, background services, or unread badge counters. The feed refreshes strictly when the user opens the app (`ON_START`) or taps **Refresh**.
2. **Pluggable `FeedPolicy` (Feed vs. Search Separation)**
   - The main feed only shows recent emails matching an extensible [`FeedPolicy`](app/src/main/kotlin/dev/hm02/mail/policy/FeedPolicy.kt) (defaults to 24 hours in the template, or can be set to `10.minutes` for ultra-minimalist clients).
   - Every other historical email stays on the server and is accessed on demand via the **Search Bar**.
3. **Multi-Account Support with Zero OAuth / CASA Audit Fees**
   - Connects directly via **IMAPS + SMTPS/STARTTLS** using **App Passwords**.
   - Includes built-in server presets and one-tap deep links to generate App Passwords for **Gmail**, **Outlook / Hotmail**, **iCloud**, **Yahoo**, **Fastmail**, and **Custom IMAP/SMTP**.
   - Credentials are encrypted at rest using 256-bit AES-GCM backed by the Android hardware KeyStore ([`AccountStore`](app/src/main/kotlin/dev/hm02/mail/data/AccountStore.kt)).
4. **Strictly Bare-Bones Actions**
   - **Feed:** Merged chronological feed across all connected accounts.
   - **Search & Read:** Parallel server-side search across all accounts + safe HTML (`WebView` with JavaScript disabled) and plain-text rendering.
   - **Compose, Reply, & Forward:** Multi-account sender selection and proper RFC 2822 threading headers (`In-Reply-To` and `References`).

---

## Project Structure

```text
app/src/main/kotlin/dev/hm02/mail/
├── MainActivity.kt              # Entry point & FeedPolicy wiring
├── model/
│   └── EmailModels.kt           # Account, EmailSummary, EmailDetail, Draft, Provider presets
├── policy/
│   └── FeedPolicy.kt            # Pluggable feed time-window & custom filtering rules
├── data/
│   ├── AccountStore.kt          # Android KeyStore AES-GCM encrypted multi-account storage
│   └── MailClient.kt            # Pure Kotlin Coroutines + Jakarta Mail IMAP/SMTP engine
└── ui/
    ├── main/
    │   ├── HM02ViewModel.kt     # UI state holder & lifecycle-driven feed refresh
    │   └── MainScreen.kt        # Compose UI: Feed, Search, Reader, Composer, Account Setup
    └── theme/                   # Material 3 theme
```

---

## Building Your Own Version (Customizing `FeedPolicy`)

To create your own opinionated email client based on HM02 (for example, showing only emails received in the **last 10 minutes** when the user opens the app), update `activeFeedPolicy` in [`MainActivity.kt`](app/src/main/kotlin/dev/hm02/mail/MainActivity.kt):

```kotlin
import dev.hm02.mail.policy.TimeWindowFeedPolicy
import kotlin.time.Duration.Companion.minutes

val activeFeedPolicy = TimeWindowFeedPolicy(
    window = 10.minutes,
    additionalFilter = { summary ->
        // Optional: Add custom sender, domain, or subject rules here
        true
    }
)
```

Or implement the [`FeedPolicy`](app/src/main/kotlin/dev/hm02/mail/policy/FeedPolicy.kt) interface directly for complete control over lookback windows and filtering rules.

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
