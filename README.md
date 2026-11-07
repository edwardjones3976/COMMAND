# Project COMMAND

Public GitHub home for **Project COMMAND**, a consent-driven Android alarm and execution director: it rings for a task, asks for an honest
answer, gives a first step and an optional five-minute starter, checks in, and keeps the person's own record. Everything stays on the phone.

- What is built, what was cut and is coming back, and what has never run on a real phone: [docs/status.html](docs/status.html).
- The release receipt (build digests, permissions, tests, the task-loop trail, known limits, installing and rolling back): [docs/RELEASE-RECEIPT.html](docs/RELEASE-RECEIPT.html).
- The owner's one-page pilot: [docs/PILOT-PROTOCOL.html](docs/PILOT-PROTOCOL.html).
- Owner-aligned product and engineering requirements: [Complete COMMAND build specification](docs/PRODUCT-SPEC.html).
- The rebuild's design record, which wins over any working plan: [docs/RECONSTRUCTION-DESIGN.html](docs/RECONSTRUCTION-DESIGN.html).
- Which verification steps run where: [docs/VERIFICATION.html](docs/VERIFICATION.html).

## Building

The Android project is in `android/` (JDK 21, the Gradle wrapper). The full check, as CI runs it:

```bash
cd android && ./gradlew testDebugUnitTest :domain:test :testing-jvm:test lint assembleDebug assembleRelease assembleNoexact
```

The receipt scripts have their own tests: `python3 -m unittest discover -s scripts/tests`. `scripts/reliability.sh` runs the outside-the-app
cases against an attached emulator and erases the app's data there: never point it at a phone whose alarms matter.

A build signs with the phone signing identity when `COMMAND_SIGNING_KEYSTORE` (a path outside the repository) and its three passwords and
alias are set, so that later builds install over earlier ones; see the release receipt.

This repository is public by owner direction. Do not commit secrets, credentials, signing keys, private messages, or restricted data.

## License

Copyright (c) 2026 Edward Jones. All rights reserved.

No license is granted to use, copy, modify, or distribute this software.
