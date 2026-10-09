# Shell address check

`AddAddr` checks whether the ADB shell user can put a 100.64.0.0/10 address on the phone's hotspot interface
through the `network_management` service. Shizuku without root runs as the same user (uid 2000), so the result
tells whether TiPlay's extra hotspot address can work through Shizuku on that phone.

The interface name must carry an alias (`wlan2:tp`). Without it, the system first clears the interface's IPv4
addresses, which cuts every device off the hotspot until it restarts. The tool refuses a name without an alias.

Build (needs a JDK and the Android SDK build tools):

```sh
javac --release 11 -d out AddAddr.java
$ANDROID_HOME/build-tools/36.0.0/d8 --min-api 28 --lib $ANDROID_HOME/platforms/android-37.0/android.jar --output out out/AddAddr.class
```

Run with the hotspot on (`adb shell ip -4 addr` shows its interface, e.g. `wlan2`). If the address is already
there from a root test, remove it first, or the check can only report "File exists":

```sh
adb push out/classes.dex /data/local/tmp/addaddr.dex
adb shell "su -c 'ip addr del 100.109.220.253/32 dev wlan2'"   # only on a rooted phone, only if present
adb shell CLASSPATH=/data/local/tmp/addaddr.dex app_process /system/bin AddAddr wlan2:tp 100.109.220.253/32
adb shell ip -4 addr show wlan2
```

The last line printed starts with `RESULT:`:

| Output | Meaning |
|---|---|
| `OK`, and `ip` shows both addresses | The shell user can add the address; the Shizuku method works on this phone |
| `File exists` | The permission check passed; the address was already there |
| `SecurityException` | The ROM blocks it; the shell user cannot change interface addresses |

The address goes away when the hotspot restarts. The shell user cannot remove it.

TiPlay's Shizuku hotspot address method makes the same call from the app; see
[docs/HOTSPOT_ADDRESS.md](../../docs/HOTSPOT_ADDRESS.md).
