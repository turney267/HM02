# HM02 — Minimalist Android Email Client Engine & Template

**`HM02`** is a bare-bones, open-source Android email client engine (`:hm02-core`) and standalone template (`:app`) built with **Kotlin**, **Jetpack Compose**, and **Jakarta Mail (IMAP/SMTP)**.

It is designed for digital-minimalism setups and developers who want to build calm, intentional, distraction-free email clients without background sync daemons, local SQLite caching of thousands of historical messages, or expensive OAuth/CASA compliance audits.

---

## Core Design Principles

1. **No Notifications & No Background Sync:** Declares only `<uses-permission android:name="android.permission.INTERNET" />`. There are no push notifications, background services, or unread badge counters. The feed refreshes strictly when the user opens/resumes the app (`ON_START`) or taps **Refresh**.
2. **Pluggable `FeedPolicy` & `MailPolicyConfig`:** The main feed only shows recent emails matching an extensible [`FeedPolicy`](hm02-core/src/main/kotlin/dev/hm02/mail/policy/FeedPolicy.kt) and [`MailPolicyConfig`](hm02-core/src/main/kotlin/dev/hm02/mail/policy/MailPolicyConfig.kt) (24 hours in the base template, or `10.minutes` with bulk/marketing sender filters in opinionated apps like **Fly**). Every other historical email stays on the server and is accessed on demand via the **Search Bar**.
3. **Multi-Account Support with Zero OAuth / CASA Audit Fees:** Connects directly via **IMAPS + SMTPS/STARTTLS** using **App Passwords**, with built-in server presets and one-tap deep links for **Gmail**, **Outlook / Hotmail**, **iCloud**, **Yahoo**, **Fastmail**, and **Custom IMAP/SMTP**. Credentials are encrypted at rest using 256-bit AES-GCM backed by the Android hardware KeyStore ([`AccountStore`](hm02-core/src/main/kotlin/dev/hm02/mail/data/AccountStore.kt)).
4. **Strictly Bare-Bones Actions:** Merged chronological feed, parallel server-side search across all accounts, sandboxed email reader (`JavaScript` disabled, optional `<video>`/`<audio>`/`<iframe>` stripping), and multi-account Compose, Reply, and Forward with RFC 2822 threading headers (`In-Reply-To` and `References`).
5. **HM03 / Surf Design Language:** Flat, tactile Zinc/Slate palette (`#09090B` / `#18181B` / `#27272A` dark; `#F8FAFC` / `#FFFFFF` / `#E4E4E7` light), bold `Monospace` headers and badges, `1dp`-bordered pill buttons (`999dp` radius), `12dp` bordered inputs, and `20dp` bordered cards.

---

## Project Structure

- **`:hm02-core`** — Reusable Android Library (`.aar`) containing [`HM02MailActivity`](hm02-core/src/main/kotlin/dev/hm02/mail/HM02MailActivity.kt), [`ImapSmtpMailClient`](hm02-core/src/main/kotlin/dev/hm02/mail/data/MailClient.kt), [`AccountStore`](hm02-core/src/main/kotlin/dev/hm02/mail/data/AccountStore.kt), [`FeedPolicy`](hm02-core/src/main/kotlin/dev/hm02/mail/policy/FeedPolicy.kt), [`MailPolicyConfig`](hm02-core/src/main/kotlin/dev/hm02/mail/policy/MailPolicyConfig.kt), and the Compose UI.
- **`:app`** — Thin wrapper producing a standalone open-source APK (`dev.hm02.mail`).

---

## Embedding `:hm02-core` in a Host App (e.g. `Fly`)

### Option A: Link via Gradle Composite Build (`settings.gradle.kts`)

```kotlin
val localHm02Dir = file("../HM02 Email")
if (localHm02Dir.exists()) {
    includeBuild(localHm02Dir) {
        dependencySubstitution {
            substitute(module("com.github.turney267:hm02-core")).using(project(":hm02-core"))
            substitute(module("com.github.turney267.HM02:hm02-core")).using(project(":hm02-core"))
        }
    }
}
```

### Option B: Consume via JitPack (`settings.gradle.kts` & `app/build.gradle.kts`)

```kotlin
dependencies {
    implementation("com.github.turney267.HM02:hm02-core:v1.0.0")
}
```

### Configure Policy at Runtime

```kotlin
import dev.hm02.mail.policy.MailPolicyConfig
import dev.hm02.mail.policy.MailPolicyProvider
import dev.hm02.mail.policy.TimeWindowFeedPolicy
import kotlin.time.Duration.Companion.minutes

MailPolicyProvider.configure(
    MailPolicyConfig(
        brandTitle = "Fly",
        feedPolicy = TimeWindowFeedPolicy(window = 10.minutes),
        blockRemoteMediaInEmails = true,
        hideBulkAutomatedSendersInFeed = true,
    )
)
```

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
