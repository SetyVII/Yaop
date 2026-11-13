# YAOP Android

Kotlin + Jetpack Compose, Room + SQLCipher, min SDK in `app/build.gradle.kts`.

```bash
./gradlew installDebug              # build and install on a connected device
./gradlew testStressUnitTest        # unit + contract tests (spec/fixtures)
./gradlew connectedStressAndroidTest  # on-device stress tests (app/src/androidTest)
```

The `stress` build type installs next to the normal app (`org.yaop.stress`) with its own data,
for tests and demo data (`../tools/phone-preview.sh`).

Layout and data flow: [../ARCHITECTURE.md](../ARCHITECTURE.md#mobile).
UI rules (no literal strings, es/en resources, editor behavior, performance): [GUIDELINES.md](GUIDELINES.md).
