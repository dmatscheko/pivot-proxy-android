# Architecture

How Pivot Proxy is put together. This is the "as-built" reference, linked from
[DEVELOPMENT.md](DEVELOPMENT.md). For build/test mechanics go there; for end-user
usage see [README.md](README.md).

The app combines two engines that can run independently or together:

- an **egress SOCKS5 proxy** (a clean Kotlin SOCKS5 server), and
- a **capturing VPN** (`VpnService` + a pure-Kotlin tun↔SOCKS bridge) with
  DNS-over-SOCKS5.

The whole app is Kotlin: there are **no custom native binaries**.

Running both at once, on the same device, is the **pivot**.

## The pivot data flow

End-to-end for `https://target`:

1. App resolves `target` → the UDP/53 query is captured by the tun, answered directly by
   **FakeDns** with a synthetic IP from `198.18.0.0/15`; the mapping `fakeIP → target` is
   remembered.
2. App connects to the fake IP → the Kotlin **TunStack** terminates the TCP locally and
   opens a SOCKS5 connection to **LocalShim**.
3. LocalShim maps the fake IP back to `target` and issues a **hostname `CONNECT`
   (ATYP=3)** to the **upstream proxy** (Burp) over a `protect()`'d socket (so it
   leaves the tun).
4. Burp inspects, then (its own upstream/SOCKS chaining) forwards the hostname
   `CONNECT` to the phone's **EgressService**.
5. EgressService resolves `target` on the **underlying** network and connects out over
   a `protect()`'d socket — egressing through the phone's real interface. Bytes pipe
   back along the chain.

DNS is never resolved on the captured-app side; the name travels as a hostname all the
way to the egress, which resolves it on-device. That is "DNS-over-SOCKS5".

## Key addresses & ports

| Name | Value | Meaning |
| --- | --- | --- |
| tun address | `26.26.26.1/24` | the device's address on the tun (`TUN_ADDR`) |
| local shim | `127.0.0.1:1081` | LocalShim, the in-app fake-IP→hostname SOCKS5 server |
| fake-IP pool | `198.18.0.0/15` | synthetic addresses handed out by FakeDns |
| DNS stub | `8.8.8.8` | advertised resolver; routed `/32` into the tun |
| egress proxy | `:1080` (default) | EgressService listen port |
| upstream proxy | `127.0.0.1:8080` (default) | the inspection proxy (Burp) |
| MTU | `1500` | tun MTU |

(IPv6, when enabled: tun `fdfe:dcba:9876::1/126`, netif `…::2`, default route `::/0`.)

## Layers

```
MainActivity (Compose, bottom-nav: Setup / Egress / VPN / Options)
   │ binds both services, mirrors their StateFlows, sends start/stop intents
   ├──────────────────────────────┬───────────────────────────────────────
EgressService (LifecycleService)   PivotVpnService (VpnService)
   • runs Socks5Server             • builds tun, runs TunStack (Kotlin)
   • protect()s its sockets        • runs FakeDns + LocalShim (fake-IP)
   • resolves on underlying net    • or direct-DNS forwarding
   │                               • registers itself with VpnProtector
socks/  (pure Kotlin, no android.*)   vpn/  (Android networking glue)
   Socks5Server → Connection → Handshake / Reply    vpn/stack/  (TunStack)
   Socks5Client (used by TunStack)
```

The `socks/` package has **no `android.*` imports** — it is plain `java.net` +
coroutines, which keeps the protocol logic unit-testable on the JVM.

## The egress proxy (`egress/` + `socks/`)

`EgressService` is a foreground `LifecycleService` that runs a `Socks5Server`. The
SOCKS5 core is reused essentially verbatim from the standalone MicroSocks project:

- `Socks5Handshake` performs method negotiation, optional RFC 1929 user/pass auth, and
  the `CONNECT` request. It takes two **injectable functions** — `resolve(host)` and
  `connect(addr, port)` — which is exactly where the pivot wiring hooks in.
- `Socks5Connection` runs the handshake then pumps bytes both directions until either
  side closes. Per-connection failures never crash the process.
- `Socks5Server` owns the accept loop (a dedicated single-thread dispatcher), a live
  connection counter, and an `onAccept` hook.

`EgressService` injects:

- **`resolve` = `underlyingResolve`** — resolves on the underlying (non-VPN) network
  via `Network.getAllByName` (see *Loop avoidance* below). **Critical** for the
  same-device pivot.
- **`connect` = `protectedConnect`** — `bind(null)` (to allocate the fd) → `protect()`
  → `connect()`, so the upstream socket bypasses our own tun.
- **`onAccept`** — `protect()`s each accepted client socket for the same reason.

## The capturing VPN (`vpn/`)

`PivotVpnService` is a `VpnService` (single process — no `:vpn` child process, no JNI,
no native code).

`configure()` builds the tun: address `26.26.26.1/24`, default route `0.0.0.0/0`
(`Routes`), the fake-IP route `198.18.0.0/15` (fake-IP mode), the `8.8.8.8/32` DNS
stub route, and optional IPv6. **Our own app is intentionally kept on the VPN** (we
never `addDisallowedApplication` ourselves) so that FakeDns replies route back into
the tun; loop avoidance is done with `protect()` instead (below).

`start()` then:

1. Picks the DNS handler for the chosen mode — fake-IP (`FakeDns.handle`) or direct
   (`forwardDns` + a `DnsCache`) — and always starts `LocalShim` (`127.0.0.1:1081`)
   with no auth. The stack **always** goes through the shim (the stack→shim hop is
   plain SOCKS5); the shim owns the real upstream's protocol, auth, the fake-IP→hostname
   mapping, the domain bypass, and — in direct mode — the `DnsCache` for name lookups.
   (See *Upstream proxy protocol* and *Domain bypass* below.)
2. Starts the Kotlin `TunStack` (`vpn/stack/`) on the tun fd, pointing it at the shim.
3. Registers itself with `VpnProtector` so the egress service can `protect()` through
   it, and publishes `VpnState.Running`.

`stopVpn()` reverses everything: stops the stack (which closes the tun fd and tears
down all flows), stops the shim, and clears `VpnProtector`.

### The TUN stack (`vpn/stack/`)

`TunStack` is the pure-Kotlin userspace TCP/IP bridge that replaces `libtun2socks.so`
(badvpn / lwIP). `PivotVpnService` hands it the `ParcelFileDescriptor` from
`Builder.establish()`; the stack reads/writes raw IP packets on that fd directly, so
there is **no child process and no fd hand-off**. It:

- **TCP** — terminates each flow with a minimal userspace TCP endpoint (`TcpFlow`):
  SYN→SYN-ACK, in-order data with windowed segmentation (MSS clamped to MTU−40 / −60)
  and a safety-net retransmit timer, FIN/RST teardown. For each new flow it opens a
  no-auth SOCKS5 connection (`socks/Socks5Client`) to `LocalShim`, `CONNECT`-ing to the
  flow's destination IP — exactly what tun2socks did. Both IPv4 and IPv6 are handled.
- **UDP/53** — handed straight to the DNS handler and the reply written back into the
  tun. Because the stack owns the packet loop, this is a direct function call: no
  `--dnsgw` redirect, no DNS-gateway socket.
- **Everything else** (other UDP, ICMP) — dropped, matching the bundled tun2socks,
  which had no `udpgw` configured.

The packet parsing/building and checksums live in `vpn/stack/Packets.kt` and
`Checksums.kt` and are unit-tested on the JVM.

### DNS modes

DNS resolution is the interesting part of a TUN VPN. The stack intercepts every UDP/53
datagram and answers it inline.

- **Fake-IP mode (default — preserves DNS-over-SOCKS5).** `FakeDns.handle` answers
  every A query with a synthetic IP from `198.18.0.0/15`, remembering `fakeIP ↔ hostname`
  (AAAA/other → NODATA so clients fall back to the fake IPv4). `LocalShim` later
  reverse-maps the fake IP and sends a **hostname `CONNECT`** to the upstream proxy,
  which (or its chain) resolves the name on the egress side. Real (non-fake) destination
  IPs pass through unchanged.

  *DNS-over-TLS:* Android's Private DNS (DoT) would otherwise carry queries over TCP/853
  and bypass `FakeDns` entirely (the captured app would receive real IPs). In fake-IP
  mode the stack therefore **refuses TCP/853** (`rejectDnsOverTls`), so an *opportunistic*
  resolver falls back to plaintext UDP/53 — which `FakeDns` answers locally, so no real
  DNS query ever leaves the device. The exception is **strict** Private DNS ("hostname"
  mode): blocking :853 would break resolution outright, so it is left alone (and fake-IP
  DNS won't apply). DNS-over-HTTPS (port 443) is indistinguishable from normal HTTPS and
  is not intercepted.

- **Direct mode (leaky).** `PivotVpnService.forwardDns` forwards each raw query off the
  tun and the stack writes the answer back. It forwards either to the **underlying
  network's own DNS servers** (default — `Networks.dnsServers` via `LinkProperties`,
  with the socket bound to that network) or to a **manual resolver** the user
  specifies (over a `protect()`'d socket). Names then resolve on the device's network,
  not the proxy's.

The UI exposes this as a cascade on the VPN tab: *DNS over SOCKS5* → (off) *use
underlying network's DNS* → (off) *manual DNS server/port*.

### Upstream proxy protocol (SOCKS5 or HTTP/S)

The upstream/inspection proxy can be reached either as a **SOCKS5** proxy or as an
**HTTP/S `CONNECT`** proxy. The latter is required for **Burp Suite**, whose proxy
listener accepts HTTP/S proxy connections but not SOCKS5. The VPN tab exposes this as
a *Proxy type* choice (`AppSettings.upstreamType`).

The stack→shim hop is plain SOCKS5, so the HTTP `CONNECT` is performed by `LocalShim`:

- **SOCKS5 upstream:** `LocalShim.upstreamConnect` issues a SOCKS5 `CONNECT`.
- **HTTP/S upstream:** `LocalShim.httpConnect` sends `CONNECT host:port HTTP/1.1`
  (with optional `Proxy-Authorization: Basic …`), reads the response head up to the
  blank line, and maps a `2xx` status to SOCKS reply `0x00` for the stack. The
  hostname is carried verbatim in the `CONNECT` line, so DNS-over-proxy is preserved
  exactly as with SOCKS5.

Either way every flow passes through `LocalShim` (the stack always points at it), so
the shim is the single place that owns upstream protocol/auth and the per-flow routing
decisions below.

### Domain bypass

Hosts on the **bypass list** (`AppSettings.bypassDomains`, suffix-matched) skip the
upstream proxy and connect **straight to the internet** — handy for an untrusted/pinned
dependency that breaks under interception. `LocalShim.connectDirect` resolves the name
on the underlying (non-VPN) network and opens a `protect()`-ed socket to the real
server, so the app talks to the genuine endpoint (real cert, no Burp) and egresses
directly.

The decision needs a hostname, which the shim obtains differently per DNS mode:

- **Fake-IP mode:** the hostname comes from `FakeDns` (the fake IP already maps to it).
- **Direct mode:** the app resolved the name itself and connects by real IP, so the
  name is otherwise lost. `DnsCache` rebuilds an `IP → hostname` map by parsing the DNS
  answers `forwardDns` returns; the shim looks the destination IP up there. This
  reverse map is used **only** for the bypass match — non-bypassed flows in direct mode
  still `CONNECT` to the real IP, keeping resolution on-device. *Caveat:* several names
  can share one IP (CDNs), so in direct mode the recovered name is best-effort.

### Per-app capture

`PivotVpnService.applyAppFilter` restricts which apps enter the tun, via the standard
`VpnService.Builder` allow/deny lists (`AppSettings.appFilterMode` + `appList`):

- **Only selected** (`addAllowedApplication`) — capture just those apps; our own
  package is always added too so `FakeDns` replies still route back into the tun.
- **All except selected** (`addDisallowedApplication`) — the listed apps bypass the VPN
  entirely (we never disallow ourselves).

Because the kernel routes by app UID, an excluded app's traffic never touches the tun
at all, so this works identically in every DNS/proxy mode and needs no per-flow logic.
The picker (`Apps.networkApps`) lists INTERNET-holding apps; enumerating them needs the
`QUERY_ALL_PACKAGES` manifest permission (Android 11+ filters package visibility).

## Loop avoidance (the subtle part)

Because the app stays **on** its own VPN, every socket the app opens that must reach
the real network has to be told to bypass the tun — otherwise it loops back into the
stack. The rules:

1. **LocalShim → upstream proxy**: `protect()`'d (`bind(null)` first, so the fd exists).
2. **EgressService upstream `connect()`**: `protect()`'d (via the injected `connect`).
3. **EgressService accepted sockets**: `protect()`'d (via `onAccept`) so reply packets
   for inbound connections also bypass the tun.
4. **EgressService DNS resolution**: done on the **underlying network**
   (`Network.getAllByName`). This is easy to miss: if the egress used the default
   resolver while our VPN is up, its own query would be captured by the tun and (in
   fake-IP mode) return a fake IP, breaking the pivot. `Networks.underlying()` picks a
   non-VPN, internet-capable, preferably-validated network.
5. **Direct-DNS forwarder** (`forwardDns`): bound to the underlying network, or
   `protect()`'d for a manual resolver.
6. **TunStack → LocalShim** is loopback (`127.0.0.0/8`), which is never routed through
   a tun, so it needs no protection. The stack reads/writes the tun fd directly and
   opens no socket for the capture path itself.

`VpnProtector` is a tiny process-global holder: `PivotVpnService` registers itself
while running, and `EgressService` calls `VpnProtector.protect(socket)` — which is a
no-op returning success when no VPN is active, so the egress proxy also works
standalone.

## State, settings, UI

- **`States.kt`** — `ServerState` (egress: Off/Starting/Running(addresses, port,
  connections)/Error) and `VpnState` (Off/Starting/Running(upstream, dnsOverProxy,
  upstreamType)/Error). Each service exposes its state as a `StateFlow` over a local
  binder and in its foreground notification.
- **`settings/Settings.kt`** — one immutable `AppSettings` over Jetpack DataStore,
  covering both engines (egress port/bind/auth, upstream host/port/type/auth, DNS mode,
  domain bypass list, per-app filter mode + package list, ipv6, start-on-boot). Services
  read settings fresh at start; the UI never pushes config into a running engine (inputs
  are disabled while running).
- **`MainActivity.kt`** — a single Compose screen with a Material 3 bottom
  `NavigationBar`: **Setup** (live status dashboard + how-to), **Egress**, **VPN**,
  **Options**. The Egress and VPN nav items show a status dot (green = running, grey =
  stopped) via `BadgedBox`/`Badge`. The activity binds both services, mirrors their
  `StateFlow`s with `collectAsStateWithLifecycle`, requests `POST_NOTIFICATIONS`
  before first start, and runs the `VpnService.prepare` consent flow.
- **`BootReceiver.kt`** — optionally starts the **egress** proxy on boot. The VPN is
  never auto-started: it requires interactive user consent.

## Concurrency & robustness

- Egress accept loop runs on a dedicated single thread; each connection relays on two
  IO coroutines; a `SupervisorJob` scope means one failed connection can't take down
  others, and `stop()` cancels everything at once.
- The `TunStack` runs a dedicated reader thread, a cached worker pool (one SOCKS
  connection + two pumps per flow), and a periodic retransmit/idle ticker; `LocalShim`
  runs its own accept thread. All are stopped explicitly on VPN teardown.
- The guiding rule (inherited from MicroSocks): **never crash the process for a
  per-connection problem** — bad handshake, refused upstream, mid-relay reset, or a
  buffer `OutOfMemoryError` all result in a clean close, not a crash.
- **Half-close-aware relays.** Both relays (`Socks5Connection.relay` for the egress and
  `LocalShim.copyOneWay`) propagate a one-directional EOF as a `shutdownOutput()` rather
  than tearing the whole connection down, and only fully close once *both* directions
  finish (a real error still closes both, so a reset can't leave the peer hanging). This
  matters for clients that finish sending and then wait for the response on a half-closed
  socket. `TcpFlow` mirrors this: an app FIN half-closes the SOCKS socket, and a SOCKS
  EOF sends a FIN to the app.

## Native dependency status

The app is **fully native-free**: all three of the original SocksDroid native
libraries have been replaced by Kotlin.

| Original SocksDroid native lib | Status here |
| --- | --- |
| `libtun2socks.so` (badvpn / lwIP) | removed → `vpn/stack/` (`TunStack`, `TcpFlow`, `Packets`, `Checksums`) + `socks/Socks5Client` |
| `libsystem.so` (JNI fd hand-off) | removed → no hand-off; the stack reads the tun fd directly |
| `libpdnsd.so` (direct DNS) | removed → `PivotVpnService.forwardDns` |

(The only `.so` files in the APK are stock Jetpack ones — `androidx.graphics.path`,
`datastore_shared_counter` — not project code.)

## Non-goals

SOCKS4, UDP `ASSOCIATE` / general UDP forwarding (only DNS is handled today), and the
`BIND` command are intentionally omitted. The upstream proxy may be SOCKS5 or an
HTTP/S `CONNECT` proxy (the latter for Burp), but the on-device **egress** listener is
SOCKS5-only. Capture scope can be narrowed by domain (bypass list) and by app (allow/
deny). An IPv6-everywhere path exists only minimally. The focus is a reliable TCP + DNS
pivot.
