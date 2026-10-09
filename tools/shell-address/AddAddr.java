// SPDX-License-Identifier: GPL-3.0-only
// Checks whether the ADB shell user (the same uid 2000 that Shizuku uses without root) can add an
// address to the hotspot interface through the network_management service. See README.md and docs/HOTSPOT_ADDRESS.md.
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class AddAddr {
    public static void main(String[] args) {
        int code;
        try {
            code = run(args);
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
            System.out.println("FAILED " + cause.getClass().getName() + ": " + cause.getMessage());
            String text = String.valueOf(cause.getMessage());
            if (cause instanceof SecurityException) {
                System.out.println("RESULT: this ROM does not let the shell user change interface addresses.");
                code = 2;
            } else if (text.contains("File exists") || text.contains("EEXIST")) {
                System.out.println("RESULT: the permission check passed; the address was already on the interface.");
                code = 3;
            } else {
                cause.printStackTrace(System.out);
                code = 1;
            }
        }
        System.exit(code);
    }

    private static int run(String[] args) throws Exception {
        if (args.length != 2 || !args[0].matches("[A-Za-z0-9_.-]{1,12}:[A-Za-z0-9]{1,3}") || !args[1].matches("100\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/32")) {
            System.out.println("usage: AddAddr <interface>:<alias> 100.x.y.z/32   e.g. AddAddr wlan2:tp 100.109.220.253/32");
            System.out.println("The alias is required: without it the system first clears the hotspot's own address.");
            return 64;
        }
        Class<?> build = Class.forName("android.os.Build$VERSION");
        System.out.println("android=" + build.getField("RELEASE").get(null) + " sdk=" + build.getField("SDK_INT").get(null)
            + " patch=" + build.getField("SECURITY_PATCH").get(null) + " uid=" + Class.forName("android.os.Process").getMethod("myUid").invoke(null));
        String iface = args[0].substring(0, args[0].indexOf(':'));
        String[] parts = args[1].split("/");
        InetAddress address = InetAddress.getByName(parts[0]);
        System.out.println("before: " + addresses(iface));

        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "network_management");
        if (binder == null) throw new IllegalStateException("network_management service not found");
        Class<?> ibinder = Class.forName("android.os.IBinder");
        Object service = Class.forName("android.os.INetworkManagementService$Stub").getMethod("asInterface", ibinder).invoke(null, binder);
        Class<?> linkClass = Class.forName("android.net.LinkAddress");
        Object link = linkClass.getConstructor(InetAddress.class, int.class).newInstance(address, Integer.parseInt(parts[1]));
        Class<?> configClass = Class.forName("android.net.InterfaceConfiguration");
        Object config = configClass.getConstructor().newInstance();
        configClass.getMethod("setLinkAddress", linkClass).invoke(config, link);
        Method set = service.getClass().getMethod("setInterfaceConfig", String.class, configClass);
        set.invoke(service, args[0], config);

        System.out.println("after:  " + addresses(iface));
        System.out.println("OK");
        System.out.println("RESULT: the shell user added the address; Shizuku without root can do the same.");
        return 0;
    }

    private static String addresses(String name) throws Exception {
        NetworkInterface iface = NetworkInterface.getByName(name);
        if (iface == null) return "(no interface " + name + ")";
        StringBuilder out = new StringBuilder();
        for (InetAddress a : Collections.list(iface.getInetAddresses())) {
            if (a instanceof Inet4Address) out.append(a.getHostAddress()).append(' ');
        }
        return out.toString().trim();
    }
}
