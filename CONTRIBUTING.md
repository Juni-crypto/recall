# Contributing

Bug reports, bank formats and pull requests are all welcome.

## Setup

See [Build](README.md#build). Then:

```bash
./gradlew testOnlineDebugUnitTest   # parser + detector tests, no phone needed
./gradlew installOnlineDebug        # needs a phone over adb
```

Debug builds can load made-up data for screenshots:

```bash
adb shell am start -n app.recall/app.recall.ui.MainActivity --ez seed_demo true
```

This wipes the app's database. Only use it on a test install.

## Where things live

```
app/src/main/java/app/recall/
  capture/     notification listener, SMS + call log readers, WhatsApp import
  understand/  request detection, urgency, MoneyParser, categories
  learn/       Qwen triage, merchant labels, reply habits, facts
  model/       model catalog, llama.cpp bridge, prompts, Safety (heat + battery guard)
  digest/      9:30 PM digest
  chat/        Ask
  data/        SQLite schema and queries
  status/      widget + lock-screen card
  ui/          Compose screens, the orb shader
app/src/main/cpp/   JNI + llama.cpp submodule
app/src/online/     model downloader (Standard edition)
app/src/offline/    no-network stub (Offline edition)
docs/               website (GitHub Pages) + screenshots
```

How the pieces fit: a notification is normalized and stored, then rules tag it right away. Every 15 minutes Qwen reviews the messages from people and decides NEED or FYI, the ask and the urgency. SQL computes every total. The model only writes words.

## Good first issues

- **Your bank's SMS isn't parsed.** Open a [bank format issue](https://github.com/Juni-crypto/recall/issues/new?template=bank_sms.yml) with the message redacted, or add a case to `MoneyParserTest.kt` and fix `MoneyParser.kt`.
- **Requests in your language.** The rules in `RequestDetector.kt` only know English ("can u", "please", "any update"). Add phrases for Tamil, Hindi or your language, with tests in `RequestAndUrgencyTest.kt`.
- **OEM quirks.** If your phone kills the listener or hides the lock-screen card, tell us the model and Android version.

## Rules

- **No personal data in code, tests or screenshots.** Use made-up names, amounts and account digits (`XX1234`).
- **Don't bypass `Safety`.** Model runs pause when the phone is hot or low on battery. Heavy jobs only run while charging.
- **Nothing new leaves the phone.** The only network call is the model download in `app/src/online`. PRs that add analytics, crash reporting or cloud calls won't be merged.
- Keep PRs small, add a test when you touch parsing or detection, and bump `MoneyParser.VERSION` when parsing changes so old payments get re-read.
