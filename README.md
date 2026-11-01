# Cast Remote for iOS — build repo

SwiftUI port of Cast Remote (Android v1.2): play a YouTube link on a chosen
TV, mirror what's playing to all TVs and phones, stop playback, and a
phone/tablet receiver mode so devices running this app show up as cast
targets. One universal IPA works on iPhones and iPads.

## Status: never compiled

These sources were written on a Linux machine with no Mac, so they have never
been compiled. The first CI build will very likely need small fixes — see
"First-build fixes" below. The YouTube-on-TV protocol itself is a faithful
port of the exact `pychromecast` + `casttube` code the Android app runs
successfully on real TVs.

## Build (GitHub Actions, no Mac needed)

1. Create a GitHub repo and upload the contents of this folder
   (`git init`, commit, push to `main`).
2. The `Build IPA` workflow runs automatically: it installs XcodeGen and
   CocoaPods, generates the Xcode project, installs the Google Cast SDK pod,
   archives **unsigned** (`CODE_SIGNING_ALLOWED=NO`), and packages
   `CastRemote.ipa`.
3. Download the IPA from the workflow run's artifacts (`CastRemote-ipa`).
4. If the build fails, fix the error in the Swift sources (see below),
   commit, and push again — the workflow re-runs.

No signing identity, no entitlements file, and no paid Apple Developer
account are involved anywhere in this repo. `Podfile.lock` is intentionally
committed (it is generated on the first successful `pod install`) so later
builds pin the same Cast SDK version.

## Install with AltStore (free Apple ID)

1. On a Windows PC, install AltServer from
   https://altstore.io and launch it (it sits in the system tray).
2. On each iPhone/iPad: connect to the PC (USB or same Wi-Fi), open
   AltServer's menu, and install AltStore. Sign in with a free Apple ID
   when asked. Repeat for every device that should run Cast Remote.
3. Download `CastRemote.ipa` to the iPhone/iPad and open it in AltStore
   (Share sheet → AltStore, or AltStore → My Apps → +). AltStore re-signs
   the app with your free Apple ID on install.
4. On first launch, allow the local-network permission prompt — the app
   cannot find TVs or phones without it.

## Free Apple ID limits (read this)

- **Apps expire after 7 days.** Keep AltServer running on the PC and the
  iPhone/iPad on the same home Wi-Fi: AltStore refreshes apps automatically
  in the background. If an app expires (it stops opening), open AltStore on
  that device while on home Wi-Fi and tap Refresh All — no reinstall needed
  unless it has been expired for a long time.
- **Receiver mode is foreground-only.** A receiving phone must keep Cast
  Remote open on screen. iOS suspends background network servers, so a
  phone with the app in the background will not receive casts.
- **No multicast entitlement.** This build deliberately has no
  `com.apple.developer.networking.multicast` entitlement (a free Apple ID
  cannot get one). Discovery uses the system Bonjour service through the
  Cast SDK and `NetService`, which should not need it. **Device-test item:**
  if the app finds phones but no TVs on first run, the missing multicast
  entitlement is the prime suspect — there is no free-account workaround
  for it, so report that result before trying anything else.

## First-build fixes (items 1-3 FIXED Nov 1, verified against the Cast SDK 4.x docs)

The sources were written against the Cast SDK v3-style listener APIs. The
three fixes below were applied Nov 1 to `Sources/CastManager.swift` (both
here and in the original `cast-remote-ios/` copy — the two `Sources/`
folders are byte-identical). Signatures were checked against the official
Cast SDK reference pages for `GCKDiscoveryManager`,
`GCKDiscoveryManagerListener`, and `GCKSessionManagerListener` before
writing them; the app has still never been compiled, so the first CI build
is the real test.

1. **FIXED — Compile error — `isDiscovering` does not exist.** In
   `refresh()`, `context.discoveryManager.isDiscovering` is now
   `context.discoveryManager.discoveryActive` (the v4 property, confirmed
   on the `GCKDiscoveryManager` reference page: `BOOL discoveryActive`,
   read-only).
2. **FIXED — Discovery listener methods were v3-style and dead in v4.**
   The `GCKDiscoveryManagerListener` extension is rewritten with the v4
   callbacks, preserving the old behavior (insert → add as IDLE and
   rebuild; update → refresh the friendly name and rebuild; remove →
   drop and rebuild):
   - `didInsertDevice(_ device: GCKDevice, at index: UInt)`
   - `didUpdateDevice(_ device: GCKDevice, at index: UInt)`
   - `didRemoveDevice(_ device: GCKDevice, at index: UInt)`
   The old no-op `discoveryManagerDidStartDiscovery` /
   `discoveryManagerDidStopDiscovery` methods are dropped (no v4
   equivalent is needed). Note the reference page also lists
   `didRemoveDeviceAtIndex:` as an alternative to
   `didRemoveDevice:atIndex:` — if both are implemented, both fire, so
   only the latter is implemented here.
   - Bonus fix found while rewriting: `CastDevice.name` changed from
     `let` to `var` — the update callback assigns the new friendly name,
     which would not have compiled against a `let`.
3. **FIXED — Session listener methods were v3-style and dead in v4.**
   The `GCKSessionManagerListener` extension now uses:
   - `sessionManager(_:didStartCastSession:)` (parameter type
     `GCKCastSession` unchanged) — resumes the session continuation.
   - `sessionManager(_:didFailToStartCastSession:withError:)` — takes the
     session AND the error; the error is passed to the continuation
     exactly as before. Without this fix every play/mirror/stop would hit
     the 15-second session timeout, because the continuation was never
     resumed.

Also verify at first build (lower risk, not yet confirmed against the
headers): `GCKCastContext.setSharedInstanceWith(_:)` (the Swift sample
code uses this name, so it is probably fine),
`GCKMediaMetadata.string(forKey:)`, and
`GCKCastChannel.sendTextMessage(_:error:)`. The `Sources/` files in this
repo are byte-identical to the originals in `cast-remote-ios/`; apply any
further fixes to both copies and keep them in sync.

## First device-test checklist

1. Refresh finds the TVs (and any receiving phones).
2. Play here starts a video on a TV.
3. Mirror what's playing mirrors a playing TV to another TV and to a
   receiving phone.
4. An incoming cast opens the YouTube app on the receiving phone.
5. Stop ends playback.

## Files

- `Sources/` — the six Swift files (app entry, UI, Cast manager, YouTube
  Lounge protocol, link parser, phone receiver).
- `project.yml` — XcodeGen project: bundle id `com.stas.castremote`,
  iOS 16+, universal (iPhone + iPad), Info.plist keys for local network,
  Bonjour services, and the `youtube` URL scheme.
- `Podfile` — Google Cast SDK via CocoaPods (`google-cast-sdk ~> 4.8.4`).
- `.github/workflows/build-ipa.yml` — unsigned CI build → IPA artifact.
