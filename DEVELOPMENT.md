# Development

How to build, test, and work on Pivot Proxy. For end-user build/install
instructions see [README.md](README.md); for how the app is put together and why,
see [ARCHITECTURE.md](ARCHITECTURE.md).

## Toolchain

| Tool | Version | Notes |
| --- | --- | --- |
| Android Gradle Plugin | 8.7.3 | pinned in `gradle/libs.versions.toml` |
| Kotlin | 2.0.21 | with the Compose compiler plugin |
| Gradle | 8.11.1 | via the wrapper (`./gradlew`) |
| JDK | **17** | AGP 8.7 requires 17; newer JDKs fail to configure |
| `compileSdk` / `targetSdk` | 35 | |
| `minSdk` | 24 | Android 7.0+ |

**JDK 17 is required.** If your system `java` is newer (e.g. 21/24) the build will
fail during configuration. Reuse the JDK that ships with Android Studio:

```bash
# macOS
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# Linux
export JAVA_HOME="/opt/android-studio/jbr"
```

You also need the Android SDK with platform 35 installed, pointed to by
`local.properties` (`sdk.dir=...`). Android Studio creates this on first open.

## Project layout

Single Gradle module, `:app`. Application id / namespace: `eu.matscheko.pivot`.

```
app/src/main/
├── AndroidManifest.xml          # 2 services (egress + VPN), boot receiver
├── res/                         # themes, strings, launcher + tab/notif icons
└── kotlin/eu/matscheko/pivot/
    ├── MainActivity.kt          # Compose UI: bottom-nav with 4 tabs
    ├── States.kt                # ServerState (egress) + VpnState
    ├── NetUtils.kt              # interface enumeration for the bind picker
    ├── Networks.kt              # underlying (non-VPN) network + its DNS servers
    ├── Apps.kt                  # installed-app enumeration for the per-app picker
    ├── BootReceiver.kt          # start egress on boot (opt-in)
    ├── settings/Settings.kt     # DataStore: one AppSettings model for both engines
    ├── egress/EgressService.kt  # foreground service hosting the SOCKS5 egress proxy
    ├── socks/                   # pure-Kotlin SOCKS5 (no android.* imports)
    │   ├── Reply.kt             # constants + AuthConfig
    │   ├── Socks5Handshake.kt   # server handshake; injectable resolve/connect
    │   ├── Socks5Connection.kt  # per-connection relay
    │   ├── Socks5Server.kt      # accept loop + connection counter
    │   └── Socks5Client.kt      # client handshake used by the TUN stack
    └── vpn/
        ├── PivotVpnService.kt   # VpnService: tun builder, TunStack, DNS wiring
        ├── VpnProtector.kt      # process-global protect() bridge to the VPN
        ├── FakeDns.kt           # fake-IP DNS resolver (DNS-over-SOCKS5)
        ├── LocalShim.kt         # SOCKS5/HTTP upstream shim + domain bypass
        ├── DnsCache.kt          # direct-mode IP→hostname reverse cache (for bypass)
        ├── Routes.kt            # tun route table
        └── stack/               # pure-Kotlin userspace TCP/IP stack (replaces tun2socks)
            ├── TunStack.kt      # tun read/write loop, protocol demux, DNS, flow table
            ├── TcpFlow.kt       # per-flow userspace TCP state machine
            ├── Packets.kt       # IPv4/IPv6 + TCP/UDP parse + build
            └── Checksums.kt     # IP / TCP / UDP (pseudo-header) checksums
```

## Build

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

### Signing a release

By default `assembleRelease` falls back to the debug signing key so the APK is
installable without any setup. To sign with your own key, create
`keystore.properties` in the project root:

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

The build picks it up automatically (see `app/build.gradle.kts`). Do **not** commit
the keystore or this file.

### No native code

The app ships **no custom native binaries** — the tun↔SOCKS bridge is the pure-Kotlin
`vpn/stack/` package, so there is no `jniLibs/`, no NDK/CMake, and no
`packaging { jniLibs { … } }` in `app/build.gradle.kts`. (The only `.so` files in the
built APK are stock Jetpack libraries.) See the *Native dependency status* table in
[ARCHITECTURE.md](ARCHITECTURE.md) for what each removed library became.

## Tests

JVM unit tests (no emulator needed):

```bash
./gradlew testDebugUnitTest --rerun-tasks
```

(`--rerun-tasks` forces a real run; without it Gradle reports the task `UP-TO-DATE`
and skips execution when nothing changed since the last green run.)

- `socks/Socks5HandshakeTest` — server greeting/auth/request success + failure paths
  driven with in-memory streams and fake `resolve`/`connect`.
- `socks/Socks5ClientTest` — the client handshake the stack uses: no-auth greeting,
  IPv4 `CONNECT`, and failure when the proxy rejects the request.
- `vpn/FakeDnsTest` — A query → fake IP + reverse lookup, AAAA NODATA, malformed
  query, stable IP per host.
- `vpn/stack/PacketsTest` — IPv4/IPv6 + TCP/UDP build↔parse round-trips and checksum
  verification.
- `vpn/stack/TcpFlowTest` — SYN→SYN-ACK, data both ways, FIN teardown against a fake
  loopback SOCKS endpoint, and RST on proxy refusal.

The protocol core is deliberately injectable (`resolve`/`connect` lambdas, and the
stack's `connectSocks`/`writePacket` hooks) so most behaviour is testable on the JVM
without a tun or Android. `unitTests.isReturnDefaultValues = true` stubs the few
`android.util.Log` calls the stack makes.

## On-device testing

The two services are **not exported**, so you cannot start them with `am
start-foreground-service` from an adb shell (permission denied). Drive the UI
instead, e.g. with `uiautomator dump` + `input tap`, or just by hand.

A useful smoke test (the device needs `curl`; most do via toybox):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Egress proxy alone — on-device DNS + egress through the phone:
#   (start the Egress proxy in the app first)
adb shell 'curl -s --socks5-hostname 127.0.0.1:1080 https://example.com -o /dev/null -w "%{http_code}\n"'

# Full pivot — start the Egress proxy, then VPN capture (accept consent), then:
adb shell 'curl -s -m 20 https://example.com -o /dev/null -w "HTTP %{http_code} remote_ip=%{remote_ip}\n"'
#   fake-IP mode: remote_ip is a 198.18.x.x synthetic address (name carried to egress)
#   direct mode:  remote_ip is the real resolved address
```

Inspect listeners and the tun on-device:

```bash
adb shell 'ip addr show tun0'                        # tun up?  (26.26.26.1/24)
# The bridge is now in-process (no tun2socks child); look at the app's own threads:
adb shell 'cat /proc/net/tcp | grep -i ":0439"'      # shim LISTEN on 1081 (0x0439)
adb shell 'cat /proc/net/tcp6 | grep -i ":0438"'     # egress LISTEN on 1080 (0x0438)
```

Tested against Android 13. Note: locked-down test devices may block
arbitrary outbound IPs/DNS — use a known-allowed host (e.g. `example.com`) and prefer
the underlying-network resolver (the default) over a manual DNS server.

## Common gotchas

- **Build won't configure** → wrong JDK; use JDK 17.
- **`SDK location not found`** → missing `local.properties` (`sdk.dir`).
- **Can't start a service from adb** → services are non-exported by design; use the UI.
- **VPN up but no traffic** → the egress proxy must be reachable and resolving on the
  underlying network; check the upstream proxy host/port and the DNS mode on the VPN
  tab. See the loop-avoidance rules in [ARCHITECTURE.md](ARCHITECTURE.md).

## Architecture

For the data flow, components, the TUN stack, DNS modes, and the (subtle)
loop-avoidance rules, read **[ARCHITECTURE.md](ARCHITECTURE.md)**.
