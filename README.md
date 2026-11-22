# Muse Wear Installer (phone app)

**Naming:** the phone and watch apps share the product name
**Muse Wear Installer** — per Jonathan's direction, 2026-11-11
("Just called it Muse Wear Installer", dropping this app's
"Phone" suffix). They live on different devices and keep distinct
packages: this phone app is
`com.jonathancruzate.metamusewearinstallerphone`; the watch app is
`com.jonathancruzate.metamusewearinstaller`. Prose in the lane's
docs says "(phone app)" where the two need disambiguating.

The standalone **phone half** of the Muse Wear Installer lane: it
lets Muse put a watch-face APK on Jonathan's Galaxy Watch 8
Classic with one tap on the watch — no PC, no ADB, no Wi-Fi
debugging port.

**Why standalone:** the phone half originally shipped inside Muse
Device Assistance v1.13.0 (`wear_install_face` + `WearInstallRouter`
+ `WearStatusService` there). On 2026-11-11 Jonathan ordered
**"Make this a separate phone apk"**; this app is that split. The
Device Assistance build is withdrawn as the lane's owner (its
v1.14.0, built on the v1.13 tree for `voice_bridge`, still carries
the verb in its binary — do not use it for wear commands).

The watch half is unchanged: **Muse Wear Installer** in
`~/workspace/muse-wear-installer/`. The phone↔watch wire protocol
(`/muse-wear-installer/` paths) is identical; `WearProtocol.java`
here stays in lockstep with the watch app's `Protocol.java`. The
lane's skill (how it works / setup / how Muse uses it) is the
wear-installer skill: `~/workspace/muse-wear-installer/SKILL.md`,
installed at `~/workspace/custom-skills/local-muse-wear-installer/SKILL.md`.

## Identity

- **Package:** `com.jonathancruzate.metamusewearinstallerphone`
- **Label:** Muse Wear Installer
- **Version:** 1.0.1 (versionCode 2), minSdk 29 / targetSdk 35
- **APK:** `app/MuseWearInstallerPhone-v1.0.1.apk` — 419,142 bytes,
  SHA-256 `50c96be8e6d8ec729e78c0dab2027a0c9ba08e64c3235a381c38515b595c7955`
  (the APK *filename* keeps the `MuseWearInstallerPhone` stem — a
  filename, not the label). Build records:
  `docs/v1.0-build-2026-11-11.md`,
  `docs/v1.0.1-build-2026-11-11.md`.
- **Signer:** project-local keystore (cert SHA-256
  `af8372b47a49e30043c90db3066556f5fc062e06559cf4539c8701968fdd1fce`),
  so upgrades install in place.

## How it works

1. Muse enqueues a **`wear_install_face`** command on the Muse
   Worker's command channel **for device `wear-installer`** —
   the Worker routes that device to the **wearbridge** lane
   (KV `wearbridge:queue` / `wearbridge:cmd:<id>` /
   `wearbridge:report`), separate from Device Assistance's
   phonebridge lane.
2. `CommandService` (foreground, specialUse) polls every 30 s,
   pulls at most one command, and gates it through **`WearGate`** —
   the on-device gate that accepts *only* `wear_install_face`
   {label, https-url, sha256} (the `ShellAllowlist.checkWearInstall`
   rules, ported: https-only, host required, no userinfo, strict
   label charset, exact sha256 shape). The Worker is a transport
   only; it cannot widen the gate.
3. `WearInstallRouter` downloads the APK with **streaming SHA-256
   verification**, sends the offer (MessageClient) and streams the
   APK (ChannelClient) to Muse Wear Installer on the watch, then
   blocks up to 300 s for the watch's terminal verdict, delivered
   by `WearStatusService`. The verdict becomes the command result;
   every stage is mirrored into the **wearbridge report**'s
   `wear_install` object.
4. A timeout is a typed verdict (`watch_install_timeout`, carrying
   the last stage) — never a retry.

Flow semantics match the v1.13 record: `ok=true` only when the
watch reports `installed`; failures are typed (`sha256_mismatch`,
`watch_not_connected`, `watch_verify_failed`, `watch_failed`,
`watch_dismissed`, `watch_install_timeout`).

## Setup (on-device)

1. Install the APK (sideload).
2. Open it; enter the **Worker base URL** and **secret** (the same
   pair the mode-sync lane uses) and tap **Save & start lane**.
   Both live only in the app's private preferences — never
   hardcoded, never logged; the secret field is a password field.
3. Tap **Exclude from battery optimization** so the lane survives
   Doze.
4. The watch half still needs its one-time bootstrap:
   `~/workspace/muse-wear-installer/bootstrap/BOOTSTRAP.md`.

## Build

```bash
app/build.sh        # raw toolchain (aapt2 -> javac -> d8 -> apksigner), fleet guards
# gate tests (plain JVM):
cd tools && javac -cp ../app/src -d /tmp/wgt WearGateTest.java && java -cp /tmp/wgt:. WearGateTest   # ALL_PASS
javac -cp ../app/src -d /tmp/wpt ProtocolTest.java && java -cp /tmp/wpt:. ProtocolTest               # 23/23
javac -cp ../app/src -d /tmp/tdt TransportDirectiveTest.java && java -cp /tmp/tdt:. TransportDirectiveTest  # ALL_PASS (v1.0.2 transport decisions)
```

Vendored libraries (`app/libs`, provenance + hashes in
`app/libs-src`): play-services-wearable 19.0.0 + base 18.5.0 +
basement 18.4.0 + tasks 18.2.0, androidx.annotation-jvm 1.9.1,
androidx.collection 1.1.0 (pure-Java; GMS IPC internals use
ArrayMap). Same staging as Device Assistance / the watch app.

## Not yet proven

Everything past the phone is unproven until the watch agent's
bootstrap and one rehearsal command: Data Layer delivery to the
real Watch 8 Classic, the on-watch prompt, the PackageInstaller
session on Wear OS, and the status return. No Wear emulator is
usable in this environment, so the rehearsal is the device test.
This app has never run on the phone yet either — its first install
+ configuration happens with the bootstrap session.

## v1.0.3 (2026-11-21) — unique Muse app icon

Icon-only release (versionCode 4). Launcher icon is now the Muse Wear
Installer icon from the approved unique Muse app icon set
(`~/workspace/muse-app-logos/`): original Muse blue M on a light tile
plus a blue watch badge, same as the watch app, superseding the
2026-11-01 all-same-icon rule. Legacy mipmaps + round + adaptive icon
(background #F0F2F5, foreground at 76% centred). Verified: icon extracted
from the built APK matches the approved art; build guards + apksigner
passed. APK: app/MuseWearInstallerPhone-v1.0.3.apk, SHA-256 `54efb23e0952b157a0b6cf65fbf3cb8f3104ad8f9440f60a05be40b8fb6311b9`.
Not installed by this handling; ready to install.
