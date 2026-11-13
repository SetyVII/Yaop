# Android Mobile Client Documentation (`mobile/`)

The Android client (`org.yaop`) is written in Kotlin with Jetpack Compose, Room + SQLCipher, Android Keystore, BouncyCastle (`bcprov`), and `zstd-jni`.

> [!NOTE]
> Mobile UI conventions (localized resource strings, editor UX rules, adaptive tablet layout, privacy screen flags) are documented in [`mobile/GUIDELINES.md`](../../mobile/GUIDELINES.md). For locating visual tokens, colors, dock shapes, and components to restyle, see [`ui-customization.md`](ui-customization.md) §1.

---

## 1. Entrypoints & App Startup

- [`YaopApp.kt`](../../mobile/app/src/main/java/org/yaop/YaopApp.kt): initializes lazy singletons (`AndroidKeyStoreVault`, `EncryptedMediaStore`, `AppDatabase`, `PkmRepository`, `AppLock`, `BackupManager`, `SyncManager`), registers `ConnectivityManager` network recovery callbacks, sets the HLC node ID from the per-install device ID, pre-warms object caches, and triggers initial sync.
- [`MainActivity.kt`](../../mobile/app/src/main/java/org/yaop/MainActivity.kt): configures edge-to-edge rendering, `FLAG_SECURE` (`applySecureFlag()` when AppLock or `ImagePrivacy` is active), gates behind `LockScreen` -> `OnboardingScreen` (when no Vault Key exists) -> `MainAppContainer`. Supports debug extras (`seed_demo`, `phone_frame`, `allow_screenshots`).

---

## 2. `core/` Layer (`mobile/app/src/main/java/org/yaop/core/`)

All business logic, cryptography, storage, and wire sync live under `org.yaop.core`:

| Package | Key Files & Responsibilities |
|---|---|
| `core/model/` | `PkmObject`, `Block`, `InlineSpan`, `BlockTypeKeys`, `PropertyDefinition`, `PropertyKeys`, `HabitMath`, and `whiteboard/` data structures |
| `core/database/` | [`AppDatabase.kt`](../../mobile/app/src/main/java/org/yaop/core/database/AppDatabase.kt) (`yaop_encrypted.db` via `SupportOpenHelperFactory` SQLCipher, schema exported to `mobile/app/schemas/`), `Entities.kt` (`ObjectEntity`, `ObjectLinkEntity`, `ObjectTypeEntity`, `AssetEntity`, `ObjectFtsEntity`, `OutboxOpEntity`, `VaultMetaEntity`, `VaultDomainSeqEntity`), `ObjectDao.kt` |
| `core/repository/` | [`RepositoryContracts.kt`](../../mobile/app/src/main/java/org/yaop/core/repository/RepositoryContracts.kt) (`ObjectRepository`, `ObjectTypeRepository`, `AssetRepository`, `TagRepository`, `SyncTransferService`) and `PkmRepository` implementation with in-memory cache + save callback scheduling outbox pushes |
| `core/crypto/` | `AndroidKeyStoreVault` (wraps DB passphrase & Vault Key with hardware Keystore), `EncryptedMediaStore` & `AssetStorage`, plus pure-Kotlin [`core/crypto/vault/`](../../mobile/app/src/main/java/org/yaop/core/crypto/vault) (`VaultKey`, `VaultKeyStore`, `VaultCryptoSession`, `Hkdf`, `Aead` XChaCha20-Poly1305, `Envelope` with zstd level 3, `IdBlinder`, `Bip39` + `Bip39Assets`/`Bip39Runtime`, `Pairing`) |
| `core/sync/` | [`SyncManager.kt`](../../mobile/app/src/main/java/org/yaop/core/sync/SyncManager.kt) (push/pull/SSE stream with exponential jittered backoff `liveBackoffMs`), `BlindTransport.kt`, `MergeContract.kt`, `SyncContractCodec.kt`, `SyncPolicy.kt`, and `ops/` (`RemoteOpPipeline`, `StandardOpHandlers`, `BootstrapSnapshot`, `NodeOps`) |
| `core/pairing/` | `PairingSession`, `PairingRelay`, `TlsFingerprint` (QR pairing flow + ZXing QR generation/scan) |
| `core/backup/` | `BackupContainer`, `BackupManager` (`.yaop-backup` encrypted export/import) |
| `core/query/` | `QueryEngine`, `SavedSets` (matching [`spec/query.md`](../../spec/query.md)) |
| `core/security/` | `AppLock`, `AppLockPolicy` (biometric/PIN lock & timeout) |
| `core/network/` | `LinkPreviewFetcher`, `LinkPreviewContracts`, `NetworkPreferences` |
| `core/debug/` | Demo vault seeder (`DemoSeeder`) and preview phone frame helper |

---

## 3. `ui/` Layer (`mobile/app/src/main/java/org/yaop/ui/`)

- **`ui/theme/`** — colors, background brush, typography, shapes, button styles, spring motion (`Color.kt`, `Theme.kt`, `Type.kt`, `Motion.kt`).
- **`ui/main/` & `ui/tablet/`** — adaptive container switching between compact bottom navigation (`FloatingPillDock` + `RadialQuickCaptureOverlay`) and tablet rail/dual-pane layout (`TabletNavigationRail`, `TabletDualPaneScaffold`).
- **Feature packages:** `ui/home/`, `ui/daily/`, `ui/tasks/`, `ui/habits/`, `ui/spaces/`, `ui/editor/`, `ui/whiteboard/`, `ui/onboarding/`, `ui/lock/`, `ui/settings/`, `ui/share/` (Android share receiver), and shared UI components in `ui/common/`.

---

## 4. Build Variants & Tests

From [`mobile/app/build.gradle.kts`](../../mobile/app/build.gradle.kts) & [`mobile/README.md`](../../mobile/README.md):

- **`stress` build type:** adds `.stress` `applicationIdSuffix`, giving isolated data & Android Keystore keys with no server token. Used as the default `testBuildType`.
- **Differential fixture tests:** forward `YAOP_SPEC_DIR` and `YAOP_ROUNDTRIP_DUMP` to Gradle unit tests for cross-platform verification.

```bash
cd mobile && ./gradlew testStressUnitTest

# Or via the repository check script
./tools/check.sh mobile
```
