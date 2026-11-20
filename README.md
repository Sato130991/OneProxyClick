# OneProxyClick

On-device HTTP/HTTPS debugging proxy for Android (minSdk 29 / targetSdk 35), Burp-style:
**Proxy history · Intercept · Repeater · Tools (CA cert, Decoder)**, controlled by a one-tap
floating bubble.

## Modules

| Module | Responsibility |
|---|---|
| `:app` | Application graph, guided permissions (VPN → overlay → notifications), Compose UI (4 tabs) |
| `:core:ca-manager` | Root CA "OneProxyClick CA" (BouncyCastle), per-host leaf certs with SAN (`LruCache`), export to `Download/`, installer intent |
| `:core:proxy-engine` | Netty proxy on `127.0.0.1:8080`: HTTP, `CONNECT` + TLS MITM (`SniHandler`), WebSocket upgrade relay, blind pass-through tunnels |
| `:core:vpn-tunnel` | `ProxyVpnService`: TUN `10.0.0.2/32` + `setHttpProxy(127.0.0.1:port)`, anti-loop exclusions, split tunneling (DataStore) |
| `:core:interceptor` | HTTP model, raw parser/serializer, gzip/deflate/br decoding, `InterceptorBus` (StateFlow + `CompletableDeferred`), Match & Replace |
| `:core:storage` | Room history (`HttpTransactionEntity`), filters, large-body overflow files |
| `:feature:overlay` | `FloatingOverlayService` (`TYPE_APPLICATION_OVERLAY`): LED bubble + mini HUD |
| `:feature:repeater` | Multi-tab raw request editor, OkHttp (no proxy), Pretty/Raw/Headers/Hex viewer, send history |
| `:feature:decoder` | Chained Base64 / Base64URL / URL / Hex / HTML / Gzip / JWT |

Dependency direction: `app → feature:* → core:*`; `proxy-engine → ca-manager, interceptor`;
`storage → interceptor`; `overlay → vpn-tunnel, interceptor`. The VPN module does not know the engine:
`:app` registers a `TunnelLifecycleHook` that starts/stops Netty together with the tunnel.

## How traffic is captured

The VPN does **not** forward packets. It only exists so Android applies its HTTP proxy
(`VpnService.Builder.setHttpProxy`, API 29+) to every app in the VPN's UID range. Proxy-aware
HTTP stacks (WebView/Chrome, OkHttp, HttpURLConnection, most SDKs) then connect to
`127.0.0.1:<port>`. Only an unused `/32` is routed into the TUN, so everything else keeps using
the underlying network. Consequences:

* Apps that ignore the system proxy (Flutter/Dart `HttpClient`, some games, raw sockets, QUIC) are not captured.
* HTTPS decryption needs the CA in the user store **and** an app that trusts user CAs (Android 7+:
  `network_security_config`, debuggable builds, Chrome). Pinned apps show up as
  "Client TLS handshake failed" — add them to *TLS pass-through hosts*.
* This app, `com.termux` and `com.kaliterm.app` are always excluded (`addDisallowedApplication`) so
  the proxy's own upstream connections and terminal tooling never loop back.

## Design notes / deliberate deviations

* **Netty**: uses `netty-handler` + `netty-codec-http` instead of `netty-all` (which bundles native
  transports and codecs that cannot load on Android and duplicates `META-INF` resources).
* **Brotli** decoding uses `org.brotli:dec` (pure Java); Netty's Brotli codec needs native `brotli4j`.
* **Leaf keys**: one RSA-2048 key per process is shared by all leaf certs; each host only costs one
  signature (fast on phones). Server `SslContext`s are cached per host.
* **History bodies**: bodies ≤ 512 KB are stored inline; larger ones go to `filesDir/bodies/`
  (SQLite's CursorWindow is 2 MB per row, so 2 MB inline would crash row reads). History copies of
  payloads > 32 MB are truncated; proxied messages are capped at 32 MB (`HttpObjectAggregator`).
* **Upstream TLS verification** is on by default; disable it in *Tools › Settings* for self-signed
  test servers. The Repeater has its own "Ignore TLS errors" toggle (on by default).
* **CA install on Android 11+**: `KeyChain.createInstallIntent()` no longer installs CA certs; the
  screen exports the cert to `Download/` and links to Security settings.
* Any local app can connect to `127.0.0.1:<port>`; stop the tunnel when you are not debugging.

## JSON export

Files go to `/sdcard/Download/OneProxyClick_Exports/` through `MediaStore.Downloads`. On Android 10–15
an app can create and append to its own files in Download without any permission, so the app requests
neither `WRITE_EXTERNAL_STORAGE` nor `MANAGE_EXTERNAL_STORAGE`.

| Action | Where | File |
|---|---|---|
| **Guardar JSON** | Proxy › transaction detail | `<METHOD>_<HOST>_<yyyyMMdd_HHmmss_SSS>.json` (pretty) |
| **Exportar JSON** | Proxy tab, top bar | `history_<stamp>.json` holding every row that matches the active filters, oldest first |
| **Auto-JSON GET/POST** | Proxy tab switch or Tools › Settings | `live_capture_YYYY-MM-DD.jsonl`, one line per completed GET/POST |

Each transaction has `id, timestamp_iso, duration_ms, method, scheme, host, port, path, query, full_url,
edited, error`, plus `request` / `response` objects (`status_code`, `headers`, `body_text`,
`body_base64` with the exact wire bytes, and `body_json` when the decoded body is valid JSON).
Repeated headers such as `Set-Cookie` become arrays. Bodies over 512 KB are read back from the overflow files.
Live writes run on a single background IO coroutine after each history insert, never on a Netty thread.
If the user deletes the file, the app creates a new one. After the app is reinstalled, Android may name
the next daily file `… (1).jsonl`, because the app no longer owns the old file.

## Language / Idioma

English and Spanish: each module has `res/values/strings.xml` (EN) and `res/values-es/strings.xml` (ES).
Standard technical terms are left in English (Proxy, Intercept, Repeater, Forward, Drop, Headers,
Raw, Hex, Split Tunneling, Root CA). Change the language in *Tools › Settings › Idioma / Language*. The
choice is saved in DataStore by `:core:common` `LanguageManager`. If nothing is saved, the app uses
Spanish when the system is in Spanish and English otherwise. The change takes effect immediately:

* Compose: `MainActivity` provides a `LocalContext` with localized `Resources`, so every
  `stringResource` recomposes without recreating the Activity.
* Floating overlay: rebuilds its HUD in place (position kept) and re-posts its notification.
* VPN service: re-posts its notification with the new text. The tunnel is not restarted.

Not translated on purpose: CLI/API replies (`opc`, control API JSON), HTTP error pages served
by the proxy, exported JSON keys and JWT section headers. They are machine-facing or protocol output.

## KaliTerm bridge

**Control (KaliTerm → app)** is a local HTTP API bound **only to `127.0.0.1`** (port `8081`, which you
can change in *Ajustes › KaliTerm Bridge*). It replaces the earlier `am broadcast` receiver, which has
been removed: `termux-am` can't target other apps from KaliTerm. The API runs inside the app process,
so it is up whenever the app, the VPN service or the bubble is alive.

| Method | Path | Body |
|---|---|---|
| GET | `/status` | – |
| POST | `/start` | `{"port": 8080}` (optional) |
| POST | `/stop` | – (also releases held requests) |
| POST | `/intercept` | `{"mode": "on\|off\|toggle"}` |
| POST | `/filter` | `{"regex": "..."}` (`""` = all hosts) |

```bash
curl -s -H "Authorization: Bearer <TOKEN>" http://127.0.0.1:8081/status
curl -s -X POST -H "Authorization: Bearer <TOKEN>" -d '{"port":8080}' http://127.0.0.1:8081/start
```
Responses are JSON (`ok`, `code`, `message`, `status`). HTTP codes: 200, 400 bad input, 401 token,
403 host/origin, 404/405 routing, 409 needs the UI (VPN consent or a refused background start), 500.
Because every app and every web page in the phone's browser can reach 127.0.0.1, the API applies three
checks: the Bearer token (when "Exigir token" is on, compared in constant time), `Host` must be
127.0.0.1/localhost (blocks DNS rebinding), and any request with an `Origin` header is rejected
(blocks browser CSRF). The server is plain `java.net` with size limits and timeouts, and it is covered
by JVM unit tests (`./gradlew :app:testDebugUnitTest`).

**`opc-cli.sh`** (`#!/usr/bin/env sh`) works in POSIX `sh`/dash, bash and proot Ubuntu. It calls the
API with `curl --noproxy '*'`, or with `python3` urllib when curl is missing (forced with `OPC_HTTP=python`).
Both ignore `http_proxy`, so control calls never pass through the proxy. It is re-exported with the token and
API port filled in whenever Settings opens. Commands: `opc on [port] | off | status | intercept on|off|toggle |
filter "<re>" | watch | listen [port] [http|tcp] | fmt | last`.

**App → KaliTerm**: the *Send to KaliTerm* button writes `last_sent.json` (overwritten each time),
copies the `curl` command to the clipboard, and then tries, in order: an activity for
`com.kaliterm.app.RUN_COMMAND`, `ACTION_SEND` to `com.kaliterm.app`, and the share sheet.

**Streams**: with Auto-JSON on, `live_stream.jsonl` gets one line per completed GET/POST, written
straight to an unbuffered file descriptor (`tail -f` sees it immediately). *Reenviar a puerto local*
POSTs (or, in TCP mode, writes one line of) the same JSON for every captured transaction to
`host:port`, using OkHttp with no proxy, from a bounded background queue. If the receiver is down,
the oldest entries are dropped. Forwarding to the proxy's own port is refused because it would loop.

## Editions: `foss` and `play` flavors

| | `foss` (GitHub Releases) | `play` (Google Play) |
|---|---|---|
| VPN tunnel, HTTPS capture, bubble, Decoder, single JSON export | ✔ | ✔ free |
| 1 Repeater tab | ✔ | ✔ free |
| Unlimited Repeater tabs | ✔ | Pro |
| Match & Replace rules | ✔ | Pro |
| Auto-JSON GET/POST + live `.jsonl` stream | ✔ | Pro |
| KaliTerm bridge (control API on 127.0.0.1 + forward to local port) | ✔ | Pro |
| Google libraries | none | Play Billing 7 |

`ProFeatureManager` (`:core:common`) is the single gate. `foss` ships an always-unlocked
implementation. `play` uses Google Play Billing with one non-consumable `inapp` product,
**`oneproxyclick_pro_lifetime`** (`BuildConfig.PRO_PRODUCT_ID`). Create it in the Play Console before
release. Purchases are acknowledged, pending payments unlock only once completed, and a refund
revokes Pro on the next store query. The entitlement is cached so Pro also works offline.
Locks are enforced in the logic, not only in the UI: a locked feature writes nothing, sends nothing,
leaves the control API unbound and applies no rules. Saved rules and settings are kept and come back
when Pro is unlocked. For high-value use, add server-side purchase verification.

```bash
./gradlew assembleFossDebug     # app/build/outputs/apk/foss/debug/app-foss-debug.apk
./gradlew assemblePlayDebug     # app/build/outputs/apk/play/debug/app-play-debug.apk
./gradlew bundlePlayRelease     # AAB for the Play Store (needs a signing config)
```
A sideloaded `play` APK cannot buy anything (Billing only works for apps installed from Play),
so it stays in the free tier.

## Build (Linux ARM64 / aarch64, headless)

AGP downloads an **x86_64-only** `aapt2` from Maven, so on ARM64 the build must use a native
`aapt2` through `android.aapt2FromMavenOverride`. It must be **≥ 2.20**: Debian/Ubuntu's `aapt`
package (2.19-debian) fails with `failed to load include path …/android-35/android.jar`.

Native ARM64 `aapt2` sources that work:
* **Termux / KaliTerm** package `aapt2` (verified here: `aapt2 2.20-android-16.0.0_r4` at
  `/data/data/com.kaliterm.app/usr/bin/aapt2`, callable from a proot Ubuntu/Debian).
* Statically linked aarch64 builds of the SDK build tools (e.g. the `lzhiyong/android-sdk-tools`
  GitHub releases, 35.x). Check the binary with `aapt2 version`.
* On a real (non-proot) Ubuntu: `qemu-user-static` + binfmt can run Google's x86_64 aapt2 instead.

```bash
# 1. JDK 17 + tools (Ubuntu/Debian arm64)
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk-headless unzip curl git
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
# (JDK 21 also works; this project was verified with OpenJDK 21.)

# 2. Android command-line tools (Java-based, run fine on aarch64)
export ANDROID_HOME="$HOME/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
curl -fLo /tmp/clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q -o /tmp/clt.zip -d "$ANDROID_HOME/cmdline-tools"
rm -rf "$ANDROID_HOME/cmdline-tools/latest"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# 3. SDK packages (platform jar is architecture independent)
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > /dev/null
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-35" "build-tools;35.0.0" "platform-tools"

# 4. Point AGP at a native aapt2 >= 2.20 (adjust the path)
AAPT2=/data/data/com.kaliterm.app/usr/bin/aapt2   # or /data/data/com.termux/files/usr/bin/aapt2, or a static build
"$AAPT2" version
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=$AAPT2" >> ~/.gradle/gradle.properties

# 5. Build
cd OneProxyClick
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew
./gradlew assembleFossDebug --no-daemon
ls -lh app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

Building from `/sdcard` (FUSE, `noexec`) works only via `bash gradlew …` and is slow; copying the
project to `$HOME` first is recommended.

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (or open the APK on the device).

## First run

1. Grant VPN consent, "Display over other apps" and notifications (guided screen).
2. *Tools › CA Cert*: export → install as **CA certificate** from Settings.
3. Turn the proxy on (top switch or tap the bubble). Bubble LED: green = on, grey = off, orange = request held.
4. Double-tap / long-press the bubble for the HUD: Intercept ON/OFF, Forward / Drop / Forward all, open app.
