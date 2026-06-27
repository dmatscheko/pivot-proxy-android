# Controlling Pivot Proxy from adb

Pivot Proxy exposes a broadcast control surface so an attached PC can **configure** both
engines, **start/stop** them, and **read back** their config and live status — for
automation and integration with other tooling.

Everything is driven with `adb shell am broadcast`. Configuration changes also reflect
**live** in the app UI if it happens to be open (no restart needed).

---

## Security model

The control receiver is exported but guarded by **`android.permission.DUMP`**. That
permission is held by the `shell` user (i.e. adb) and the system, but **cannot be
granted to ordinary apps** — so in practice only `adb shell am broadcast …` (and the
platform itself) can drive it. No other installed app can. This is the same gating the
AndroidX `ProfileInstallReceiver` uses.

There is no extra setup: install the app, and the control surface is live.

---

## Quick reference

```bash
PKG=eu.matscheko.pivot
RCV=$PKG/.control.ControlReceiver

# Start / stop the engines
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.EGRESS_START
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.EGRESS_STOP
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.VPN_START
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.VPN_STOP

# Change settings (any subset; omitted keys are left as-is)
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.CONFIGURE \
  --ei egress_port 1080 --es egress_bind 0.0.0.0

# Configure-and-start in one shot (extras are persisted *before* the start runs)
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.VPN_START \
  --es upstream_host 127.0.0.1 --ei upstream_port 8080 --es upstream_type http \
  --ez dns_over_proxy true

# Read back the current config (JSON; passwords omitted) and the live engine status
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.QUERY_CONFIG
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.STATUS
```

`-n $RCV` targets the receiver explicitly; the `-a <action>` selects what to do.

---

## Actions

| Action (`eu.matscheko.pivot.action.` + …) | Effect |
| --- | --- |
| `CONFIGURE`    | Apply the config extras only. |
| `EGRESS_START` | Apply any config extras, then start the egress proxy. |
| `EGRESS_STOP`  | Apply any config extras, then stop the egress proxy. |
| `VPN_START`    | Apply any config extras, then start the VPN (if consent is granted). |
| `VPN_STOP`     | Apply any config extras, then stop the VPN. |
| `QUERY_CONFIG` | Return the current settings as JSON (see [Reading config and status](#reading-config-and-status)). |
| `STATUS`       | Return the live state of both engines as JSON. |

Any action may carry configuration extras. They are persisted **before** the start/stop
is performed, so a single broadcast can configure-and-start atomically. `QUERY_CONFIG` and
`STATUS` are read-only — they ignore any extras and never change settings.

---

## Configuration extras

Pass each with the matching `am` flag: strings `--es`, integers `--ei`, booleans `--ez`,
string arrays `--esa`. Using the wrong flag (e.g. `--es` for a port) makes the value be
ignored, with a warning in the log.

### Egress proxy

| Key | Flag | Type | Notes |
| --- | --- | --- | --- |
| `egress_port` | `--ei` | int | `1`–`65535` |
| `egress_bind` | `--es` | string | bind address, e.g. `0.0.0.0`, `127.0.0.1` |
| `egress_auth` | `--ez` | bool | require username/password |
| `egress_user` | `--es` | string | |
| `egress_pass` | `--es` | string | |

### VPN — upstream proxy

| Key | Flag | Type | Notes |
| --- | --- | --- | --- |
| `upstream_host` | `--es` | string | e.g. `127.0.0.1` |
| `upstream_port` | `--ei` | int | `1`–`65535` |
| `upstream_type` | `--es` | string | `socks5` or `http` (use `http` for Burp Suite) |
| `upstream_auth` | `--ez` | bool | proxy requires username/password |
| `upstream_user` | `--es` | string | |
| `upstream_pass` | `--es` | string | |

### VPN — behaviour

| Key | Flag | Type | Notes |
| --- | --- | --- | --- |
| `dns_over_proxy` | `--ez` | bool | fake-IP DNS over SOCKS5 (keep `true` for the pivot) |
| `direct_use_underlying_dns` | `--ez` | bool | direct-DNS mode: use the network's own resolver |
| `direct_dns` | `--es` | string | manual resolver IP (direct-DNS mode) |
| `direct_dns_port` | `--ei` | int | `1`–`65535` |
| `ipv6` | `--ez` | bool | |
| `bypass_domains` | `--es` | string | hosts that skip the proxy; comma/space/newline separated |
| `app_filter_mode` | `--es` | string | `off`, `include`, or `exclude` |
| `app_list` | `--esa` | string[] | package names, e.g. `com.example.a,com.example.b` |

### Lifecycle

| Key | Flag | Type | Notes |
| --- | --- | --- | --- |
| `start_egress_on_boot` | `--ez` | bool | |
| `start_vpn_on_boot` | `--ez` | bool | opportunistic; only fires at boot if VPN consent is already granted |

---

## Reading config and status

Both read-backs return their answer in the broadcast **result data** (the same channel
the start/stop actions use for `ok` / `error: …` — see [Feedback](#feedback)).

**`QUERY_CONFIG`** returns the current persisted settings as a JSON object. The keys match
the CONFIGURE extras exactly, so a dump reads straight back into `--es/--ei/--ez` flags.
The two passwords (`egress_pass`, `upstream_pass`) are **never** emitted.

```jsonc
{"egress_port":1080,"egress_bind":"0.0.0.0","egress_auth":false,"egress_user":"",
 "upstream_host":"127.0.0.1","upstream_port":8080,"upstream_type":"socks5",
 "upstream_auth":false,"upstream_user":"","dns_over_proxy":true,
 "direct_use_underlying_dns":true,"direct_dns":"8.8.8.8","direct_dns_port":53,
 "ipv6":false,"bypass_domains":"","app_filter_mode":"off","app_list":[],
 "start_egress_on_boot":false,"start_vpn_on_boot":false}
```

**`STATUS`** returns just the live state of each engine — the most important, always-short
read-back:

```json
{"vpn":"running","egress":"stopped"}
```

Each field carries one of these values:

| Value | Fields | Meaning |
| --- | --- | --- |
| `running` | `vpn`, `egress` | The engine is up and serving. |
| `starting` | `vpn`, `egress` | A start is in progress (transient). |
| `stopped` | `vpn`, `egress` | Not running. For `vpn`, VpnService consent **is** granted, so adb can start it. |
| `error` | `vpn`, `egress` | The last start attempt failed (the reason is in the `PivotControl` log). |
| `permission_required` | `vpn` only | Off **and** Android's VPN [connection request dialog](https://developer.android.com/develop/connectivity/vpn#:~:text=the%20system%20displays%20a%20connection%20request%20dialog) (from `VpnService.prepare()`) has not been accepted yet, so `VPN_START` would be refused until it's granted once in the app. |

`am broadcast` prints the result with the inner quotes backslash-escaped. To pull out
clean JSON (e.g. to pipe into `jq`):

```bash
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.STATUS \
  | sed -n 's/.*data="\(.*\)"/\1/p' | sed 's/\\"/"/g'
# {"vpn":"running","egress":"stopped"}
```

---

## Examples

Point the VPN at a Burp Suite running on the host (via `adb reverse tcp:8080 tcp:8080`)
and start capturing:

```bash
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.VPN_START \
  --es upstream_host 127.0.0.1 --ei upstream_port 8080 --es upstream_type http \
  --ez dns_over_proxy true
```

Capture only one target app:

```bash
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.CONFIGURE \
  --es app_filter_mode include --esa app_list com.example.target
```

Configure the egress proxy with auth, without starting it:

```bash
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.CONFIGURE \
  --ei egress_port 1080 --es egress_bind 0.0.0.0 \
  --ez egress_auth true --es egress_user me --es egress_pass secret
```

Bring everything up for a USB-tethered Burp pivot:

```bash
adb reverse tcp:8080 tcp:8080      # phone → Burp
adb forward tcp:1080 tcp:1080      # computer → phone egress

adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.EGRESS_START
adb shell am broadcast -n $RCV -a eu.matscheko.pivot.action.VPN_START \
  --es upstream_host 127.0.0.1 --ei upstream_port 8080 --es upstream_type http
```

---

## Feedback

`am broadcast` waits for the result and prints it:

```
Broadcasting: Intent { ... }
Broadcast completed: result=0, data="ok"
```

On a problem the `data` carries the reason, e.g.
`data="error: VPN consent not granted; start it once from the app first"`. The same line
is logged under the **`PivotControl`** tag:

```bash
adb logcat -s PivotControl
```

---

## Constraints (by design)

- **VPN consent.** `VPN_START` only works once Android's VPN [connection request
  dialog](https://developer.android.com/develop/connectivity/vpn#:~:text=the%20system%20displays%20a%20connection%20request%20dialog) has
  been accepted — that dialog cannot be shown from a broadcast. Start the VPN once from
  the app to grant it; afterwards adb can start/stop it freely. Until then `VPN_START`
  returns `error: VPN consent not granted …`.
- **Config is read when an engine starts.** Each engine snapshots its settings at start
  (mirroring the UI, which disables fields while an engine is running). Change config
  while the engine is **stopped**, or stop → configure → start, for it to take effect.
- **Background start limits.** Android restricts starting a foreground service from the
  background. Receiving the broadcast normally lifts this long enough to start an engine,
  but if the device is deeply idle a start may be rejected — the result `data` will say
  so. Bringing the app to the foreground once, or keeping the screen on, avoids it.

---

## Live UI

If the app is open when a `CONFIGURE` (or a configure-and-start) broadcast lands, the
relevant tab updates **in place** — both engines' settings are observed live, so you
don't need to reopen the app to see the new values.
