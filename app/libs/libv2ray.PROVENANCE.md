# libv2ray.aar provenance

This repository currently contains a locally patched binary Android archive
built from the 2dust AndroidLibXrayLite wrapper project.

- File: `libv2ray.aar`
- Upstream repository: `https://github.com/2dust/AndroidLibXrayLite`
- Upstream release: `v26.9.9`
- Xray-core: `v26.9.9`, commit `52a412d9e2f5c2a5142b1b4e2ab3771dacb8b120`
  (upstream core release is marked pre-release).
- Upstream tag commit: `d0c6c4ae1b09c912070c8288bd0dbcc2e492ac29`
- Local patch: `libv2ray-v26.9.9-cancellable.patch`
- Patch purpose: caller-bounded, explicitly cancellable `MeasureDelay` with
  generation-safe active-measurement ownership and cancellation before core
  shutdown. One-shot `OutboundProbe` exposes per-candidate cancellation without
  affecting the connected controller or another candidate. HTTP measurements read
  complete response bodies with a shared 256 KiB default body-read budget and a
  12-second total deadline; TLS verification remains enabled. Body-read budgets
  do not include transport overhead or socket read-ahead.
- Build command:
  `gomobile bind -target=android -androidapi 24 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' -o libv2ray.aar ./`
- Build toolchain: Go `1.27.1`, `golang.org/x/mobile`
  `v0.0.0-20260908204917-8b95e45f8d3e`, Android NDK
  `29.0.14206865`.
- Size: `61393658` bytes
- SHA-256: `C607054BE083E4246575ACA0D78E6CE831E25E173BF20B219DA110E372A310FA`
- Local build timestamp: `2026-09-22`
- Archive entries include `classes.jar`, `proguard.txt`, geo assets, and JNI
  libraries for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.
- Bundled `proguard.txt` keeps `go.**` and `libv2ray.**`.

Previous artifacts:

- Locally patched AndroidLibXrayLite `v26.6.2`: SHA-256
  `7448EE8CCE680872E906F9BDA78DEB37320CACD4C03AF086B11B8F94CAC70EC6`, size `55908882` bytes.
  Its cancellation/body-budget behavior is retained by the current patch.

- The unmodified AndroidLibXrayLite `v26.6.2` release asset was
  `https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.6.2/libv2ray.aar`.
- Its SHA-256 was
  `367D6B2F74E62C974C61210C56802127812BE4C9410A83A6B8B6CAC765A7595E`
  and its size was `56764555` bytes.
- The 2026-05-02 import used AndroidLibXrayLite `v26.3.23`.
- Previous SHA-256:
  `1D4F5501B01DAF909DEBA48954EB0006359652D1AA903B5E78958E1FCE5BC50E`.
- Previous size: `55670069` bytes.

Source status:

- This is the same third-party wrapper family used by v2rayNG-style Android
  clients. It is not the official XTLS/Xray-core release artifact.
- Official XTLS/Xray-core publishes Android `xray` ZIP binaries.
- Official XTLS/libXray is a source-built wrapper path and uses a different
  Android API from the current `libv2ray.*` integration.
- Do not replace this file with unrelated third-party AARs. For future updates,
  use a pinned AndroidLibXrayLite release or migrate deliberately to
  XTLS/libXray with controller/probe adapter changes.

Validation for the current patch:

- Ten native host tests passed, including real-core TLS body stalls after 16 KiB,
  complete-body reads, response budgets, cancellation, isolated concurrent probes,
  and stop cancelling measurement before closing the core with one shutdown callback.
- Four Android ABIs built successfully; Java signatures verified with `javap`.
- Signed release installed on Samsung SM-G996B / Android 15. Device logs
  confirm Xray 26.9.9 startup; AUTO connected using Reality/TCP and XHTTP
  during Wi-Fi/mobile handover. UI snapshots after refresh show connected.
  This does not prove preservation of existing flows across physical networks.

- Bundled geo assets retain the previous local build contents.
- Experimental hot-route code is not included in this AAR.
- Android release validation: 620 unit/Robolectric tests passed; lint and
  signing validation passed. All four APK outputs have verified signatures
  and native payloads matching this AAR. Full device/protocol coverage remains
  separate from this upgrade smoke check.
