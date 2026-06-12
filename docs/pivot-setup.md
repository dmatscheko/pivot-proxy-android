# Setting up the pivot

A first-run walkthrough for using Pivot Proxy with Burp Suite: bringing the two
engines up, trusting Burp's CA for HTTPS, scoping what gets captured, and giving the
phone and the computer a network path to each other. For end-user install
instructions see [../README.md](../README.md); for scripting the same thing from a PC
see [adb-control.md](adb-control.md).

---

## Bring up the pivot

1. **Egress tab** → start the **Egress proxy** (default port `1080`).
2. **VPN tab** → set the **Upstream proxy** to your Burp Suite proxy and choose the
   **Proxy type**: pick **HTTP/S** for Burp Suite (its proxy listener only accepts
   HTTP/S, not SOCKS5), or **SOCKS5** for a SOCKS5 upstream. Keep **DNS over SOCKS5**
   on.
3. In **Burp**, configure its upstream proxy / SOCKS chaining to point back at the
   phone's **egress** proxy (see *Reaching the phone* below for the address to use).
4. **VPN tab** → start **VPN capture** and accept the Android VPN consent dialog.

All device traffic now flows: apps → VPN → Burp → phone's egress proxy → internet,
with DNS resolved on the egress (phone) side.

> You don't have to use Burp. The "upstream proxy" can be any SOCKS5 or HTTP/S
> `CONNECT` proxy. Pointing the VPN's upstream (SOCKS5) directly at the local egress
> proxy gives a pure on-device pivot with no laptop involved.

---

## Trusting Burp's CA (HTTPS interception)

Burp does TLS man-in-the-middle: it presents its own CA certificate, so HTTPS apps on
the phone fail with a certificate error until the phone trusts that CA. Export it from
Burp as **Proxy → Proxy settings → Import / Export CA certificate → "Certificate in
DER format"** (save it as e.g. `cacert.der`) — the public **certificate only**, *not*
the P12.

Convert that DER to PEM for `curl` and for the hash step below (the Settings installer
accepts the DER as-is, so this is only needed for the other methods):

```bash
openssl x509 -inform DER -in cacert.der -out burp.pem
```

There is no single no-root switch that makes *every* app trust it; pick by target:

- **System store (root) — trusts all apps.** Install the cert into
  `/system/etc/security/cacerts/` named by its subject hash. Requires root (or a Magisk
  "trust user certs" module).

  ```bash
  # name the cert "<subject_hash>.0", then push it (needs a writable /system)
  HASH=$(openssl x509 -inform PEM -subject_hash_old -in burp.pem | head -1)
  cp burp.pem "$HASH.0"
  adb root && adb remount
  adb push "$HASH.0" /system/etc/security/cacerts/
  adb shell chmod 644 "/system/etc/security/cacerts/$HASH.0"
  adb reboot
  ```

- **User cert (no root) — older / opted-in apps only.** *Settings → Security →
  Encryption & credentials → Install a certificate → CA certificate*, then pick the DER
  file (give it a `.cer`/`.crt` extension). Trusted by the OS, browsers, WebViews, apps
  targeting **Android 6 (API 23) or older**, and any app that opts into user certs.
  Apps targeting API 24+ ignore the user store by default.

- **`network-security-config` (no root) — for an in-scope app you can rebuild.** Have
  the app trust user certs by adding a network-security-config, then install the user
  cert as above:

  ```xml
  <network-security-config>
    <base-config><trust-anchors>
      <certificates src="system"/>
      <certificates src="user"/>
    </trust-anchors></base-config>
  </network-security-config>
  ```

  If you can't rebuild it from source, repackage the APK with a tool like `apk-mitm`
  (adds user-cert trust and strips common pinning) — only for apps you're authorized to
  modify under the engagement scope.

> Quick check without installing anything: command-line `curl` reads the *system* CA
> bundle (not the user store), so point it at the cert directly —
> `curl --cacert burp.pem https://example.com`.

---

## Scoping what gets intercepted

Two controls on the **VPN** tab let you keep things out of scope (e.g. a pinned
dependency that breaks under interception, or apps you simply shouldn't touch):

- **Bypass domains** — a list of hosts that connect **straight to the internet**,
  skipping the proxy entirely. Subdomains match (`example.com` also covers
  `api.example.com`); one per line. Works in both DNS modes.
- **Per-app capture** — choose **Capture all apps**, **Only selected apps**
  (capture just your target), or **All apps except selected** (let a dependency
  bypass the VPN). Tap **Select apps** to pick them. Excluded apps never enter the
  VPN, so their traffic and TLS are untouched — useful when an app pins its
  certificate and can't trust Burp's CA.

> A handy pentest setup: **Only selected apps → your target app**, so Burp sees
> just that app's traffic and nothing else on the device.

---

## Reaching the phone

The two connections between the phone and the computer (Burp Suite) need a network path:

- **Phone on Wi-Fi/LAN** that the computer can reach: just use the phone's IP. The VPN
  upstream is `Burp-IP:8080`, and Burp's chain points at `phone-IP:1080` (the address
  shown on the Egress tab).

- **Phone on mobile data** (or otherwise not reachable): the phone's external IP is its
  carrier (often CGNAT) address and is **not reachable** from the computer. Tunnel both
  directions over USB with `adb` (run these on the computer):

  ```bash
  # phone → Burp: the phone reaches Burp via its own localhost:8080
  #   → set the app's VPN upstream proxy to 127.0.0.1:8080 (the default)
  adb reverse tcp:8080 tcp:8080

  # computer → phone egress: the computer reaches the egress via its localhost:1080
  #   → set Burp's upstream/SOCKS chain to 127.0.0.1:1080
  adb forward tcp:1080 tcp:1080
  ```

  `adb forward` binds only to the computer's `localhost`. To make the egress proxy
  reachable from other machines (or tools that won't use loopback), re-expose it on all
  interfaces with `socat`:

  ```bash
  socat TCP-LISTEN:1080,bind=0.0.0.0,fork,reuseaddr TCP:127.0.0.1:1080
  ```

> With `adb reverse`/`forward` the app's defaults already line up: VPN upstream
> `127.0.0.1:8080` and egress `:1080`.

---

## Troubleshooting

- **VPN starts but nothing loads** — check the upstream proxy host/port, and that the
  proxy (Burp) is reachable from the phone and chained back to the egress address.
- **HTTPS apps fail with a certificate error** — the phone doesn't trust Burp's CA for
  that app; see *Trusting Burp's CA* above and pick the method that fits the target.
- **A service stops on its own after a while** — exclude the app from battery
  optimization (Options tab).

For build/toolchain problems (JDK version, `SDK location not found`) see
[../DEVELOPMENT.md](../DEVELOPMENT.md).
