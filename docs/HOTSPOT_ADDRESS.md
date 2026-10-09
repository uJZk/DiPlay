# Hotspot address methods

In phone + browser mode the Tesla browser opens TiPlay at an address on the phone's own hotspot. The Tesla browser
blocks the private ranges (10/8, 172.16/12, 192.168/16) but opens 100.64.0.0/10, so TiPlay can give the phone an extra
address in that range. The driver picks how, in **Settings → Connection → Tesla browser → Hotspot address**:

| Method | Needs | What TiPlay does | When the driver leaves it |
|---|---|---|---|
| Normal (default) | nothing | Adds nothing. Shows the hotspot's IPv4 and warns when it is outside 100.64.0.0/10. For other car browsers, or a hotspot that already uses 100.64. | — |
| Root | root (Magisk, KernelSU …) | `ip -4 addr replace <address>/32 dev <iface>` through `su`, again after every hotspot restart | `ip -4 addr del …`: the address goes at once |
| VPN (experimental) | nothing | A VPN that holds the address and nothing else | The VPN closes |
| Shizuku | Shizuku (ADB) or Sui (root) | `INetworkManagementService.setInterfaceConfig("<iface>:tp", <address>/32)`, again after every hotspot restart | The address stays until the hotspot restarts: the shell user cannot remove it |

Every method applies at once; none reconnects CarPlay. The address must be in 100.64.0.0/10 or 169.254.0.0/16 (default
`100.109.220.253`). The first root prompt, the Shizuku permission request and the VPN consent come only from a tap in
the chooser or its buttons; building the settings search index touches none of them.

Code: `shared/src/main/java/com/shilapi/xcertplay/network/` (`HotspotExtraAddressKeeper`, `HotspotAddressBackend`,
`ShizukuHotspotAddress.kt`, `HotspotAddressVpn`, `HotspotAddressVpnService`) and
`common/src/main/java/com/shilapi/xcertplay/` (`HotspotAddressMethod`, `HotspotExtraAddressSettings`,
`HotspotExtraAddressCard`).

## Root and Shizuku: the keeper

`HotspotExtraAddressKeeper` watches the tethering and Wi-Fi AP broadcasts and polls every 5 s. When the address is
missing from the hotspot interface it calls its backend, then reads the interface to confirm. Failures back off from
5 s to 60 s. A root refusal, or a ROM that refuses the shell user, stops the session until the driver chooses the
method again in the chooser (an activity resume or a new connection does not retry). Shizuku that is not running, or has not allowed TiPlay yet, is only a waiting state: Shizuku's own events
(binder received or dead, permission answered) trigger a check at once.

The keeper touches only the phone's own hotspot ("Built-in car hotspot" connection), never a joined Wi-Fi network.
The VPN also runs only with that connection: on a joined network the car cannot reach the extra address at all.

## Shizuku

TiPlay calls `network_management` from its own process through `ShizukuBinderWrapper`, so the call runs as the user
Shizuku runs as: the ADB shell user (uid 2000), or root with Sui. It never uses a Shizuku UserService (Shizuku 13.6.0's
`bindUserService` hangs on Android 17, RikkaApps/Shizuku#2180).

- The shell user holds `CONNECTIVITY_INTERNAL`, which `NetworkManagementService.setInterfaceConfig` accepts. Checked
  on Android 16 with [`tools/shell-address`](../tools/shell-address/README.md); the Android 17 source has the same chain.
- The interface name carries the alias `:tp` (`wlan2:tp`, at most 15 characters). Without an alias netd first clears
  the interface's IPv4, which cuts every device off the hotspot. The call itself (`NetworkManagementCall`) refuses any
  other name and any prefix but /32 before it reaches the binder, whoever calls it.
- `IllegalStateException` "File exists" means the address is already there and counts as success. A
  `SecurityException` after Shizuku has allowed TiPlay means the ROM blocks the shell user.
- Removing needs `NETWORK_STACK`, which the shell user lacks; the address goes when the hotspot restarts.
- Android lists a labelled address as a virtual interface of its own (`wlan2:tp`, libcore `NetworkInterface.getAll`),
  so the keeper and the settings also read the alias and the sub-interfaces.

Dependencies: `dev.rikka.shizuku:api` and `:provider` 13.1.5 (latest on Maven Central). `rikka.shizuku.ShizukuProvider`
is declared in `shared/src/main/AndroidManifest.xml` as Shizuku's documentation requires (authority
`${applicationId}.shizuku`, exported, `INTERACT_ACROSS_USERS_FULL`, not multiprocess).

### Non-SDK interfaces (targetSdk 37)

The call reaches hidden classes by reflection. Their status in the Android 17 lists (`frameworks/base` and
`packages/modules/Connectivity` at `android-17.0.0_r1`):

| Member | Status | Allowed at targetSdk 37 |
|---|---|---|
| `INetworkManagementService$Stub.asInterface(IBinder)` | `boot/hiddenapi/hiddenapi-unsupported.txt` | yes |
| `INetworkManagementService.setInterfaceConfig(String, InterfaceConfiguration)` | `@UnsupportedAppUsage`, no `maxTargetSdk` | yes |
| `InterfaceConfiguration()`, `InterfaceConfiguration.setLinkAddress(LinkAddress)` | `@UnsupportedAppUsage`, no `maxTargetSdk` | yes |
| `ServiceManager.getService(String)` (used by Shizuku's `SystemServiceHelper`) | `@UnsupportedAppUsage`, no `maxTargetSdk` | yes |
| `LinkAddress(InetAddress, int)` and the other constructors | `@SystemApi` only | no |

So TiPlay needs no exemption from the non-SDK interface restrictions. The one member apps may not call, the
`LinkAddress` constructor, is avoided: TiPlay builds the `LinkAddress` with the public `LinkAddress.CREATOR` from a
`Parcel` in the layout `LinkAddress.writeToParcel` uses, and checks the result with the public getters, so a future
layout change fails cleanly instead of sending a wrong address. The transaction code always comes from the device's own
`Stub.Proxy`; TiPlay never hard-codes one.

## VPN (experimental)

`HotspotAddressVpnService` is separate from the wired path's `CarPlayVpnService`. Its tunnel:

- `addAddress(<address>, 32)` and no routes and no DNS servers, so TiPlay's own traffic finds no route in the tunnel and
  falls through to the hotspot (`local_network` rule) and the default network as before;
- `addAllowedApplication(<TiPlay>)`, so no other app's traffic is affected;
- `allowFamily(AF_INET6)`, so TiPlay's IPv6 is not black-holed; `setBlocking(false)`; `setMetered(false)` (metered only
  when the network underneath is);
- the file descriptor stays open and is never read or written.

The service never calls `startForeground`, so it needs no `foregroundServiceType` on Android 14 and later: while the
tunnel is up, Android binds the service itself (`BIND_FOREGROUND_SERVICE`) and shows its own VPN icon. It has no
`android.net.VpnService` intent filter and declares `SUPPORTS_ALWAYS_ON=false`, so it cannot be an always-on VPN.

**Probably does not work** on Android 15 and later, and on Android 14 from the 2025-01-01 security patch (an unreadable
patch level counts as patched): those versions drop packets that arrive on another interface for a VPN address
(CVE-2024-49734). Link-local addresses (169.254.0.0/16) are exempt, so the settings suggest `169.254.220.253` there.
Whether the Tesla browser opens a 169.254 address is not tested yet.

TiPlay does not fight other VPNs:

- Another app's VPN on (Android shows a VPN's owner only to the owner): TiPlay reports it and does not ask for consent,
  which would switch that VPN off. An always-on VPN of another app shows up the same way while it runs.
- The wired CarPlay VPN of TiPlay itself (an IPv6-only tunnel): one package has one VPN slot, and a second `establish`
  would replace the wired tunnel, so TiPlay waits. If the wired path takes the slot while the hotspot tunnel is up,
  Android drops the hotspot tunnel's binding without a revoke (only this package can replace it, and the new tunnel's
  addresses may not be visible yet): TiPlay closes it and starts it again at the next resume or connection once the
  wired tunnel is gone.
- The driver turns the VPN off in Android settings, or another VPN app takes over (`onRevoke`): TiPlay closes it and
  does not restart it by itself, neither on an activity resume nor on a new connection (`VpnService.prepare` there
  would also switch off another app's VPN that is still connecting). Only choosing VPN again or the "Allow the VPN"
  button starts it, after the same check for other VPNs. A TiPlay restart forgets the revoke.

Consent comes from `VpnService.prepare`, opened only from the chooser or the "Allow the VPN" button. The answer needs no
result handler: when the driver comes back, the activity's `onResume` starts the tunnel if Android allowed it.

## Diagnostics

The diagnostic report has one line: method, method in effect, keeper backend and state, VPN state, the Android class for
the VPN rule (`android15+`, `android14-patched`, `android14-unpatched`, `before-android14`), whether the VPN probably
fails, and whether the address is the default or link-local. Never the address.
