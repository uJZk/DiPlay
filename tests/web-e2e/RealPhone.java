// SPDX-License-Identifier: GPL-3.0-only
// The phone side of the browser link for phone-run.mjs, made of the app's own :shared classes: BrowserLinkServer with an
// asset loader over a page directory, and a WebVideoHub fed through VideoTeeMediaSink → WebMediaSink with an H.264
// Annex-B clip, the way CarPlayHostActivity.createMediaEngine wires a phone + browser session. Not part of CI; see
// README.md.
//
// Single-file launch (JDK 17 or later):
//   java -cp <shared classes>:<kotlin-stdlib.jar> tests/web-e2e/RealPhone.java --page site/play --port 0 \
//     --code 271828 --width 1280 --height 720 --fps 30 [--clip clip.h264] [--bind 0.0.0.0]
// Without --clip it streams hand-made SPS/PPS and fake slices, which are enough for the transport.
// stdout: one JSON object per line. stdin commands: restart, fit-on, fit-off, quit.
import com.shilapi.xcertplay.airplay.AirPlayContact;
import com.shilapi.xcertplay.airplay.MediaSink;
import com.shilapi.xcertplay.airplay.VideoCodec;
import com.shilapi.xcertplay.media.VideoTeeMediaSink;
import com.shilapi.xcertplay.web.BrowserLinkServer;
import com.shilapi.xcertplay.web.BrowserLinkStatus;
import com.shilapi.xcertplay.web.BrowserSize;
import com.shilapi.xcertplay.web.BrowserViewport;
import com.shilapi.xcertplay.web.WebMediaSink;
import com.shilapi.xcertplay.web.WebVideoHub;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;

public class RealPhone {
    private static final int MAIN_SCREEN = 110;

    /** One access unit: Annex-B NAL units with 4-byte start codes, without AUD, SPS, PPS or SEI (the hub adds SPS/PPS). */
    record AccessUnit(byte[] bytes, boolean key) {}

    record Clip(byte[] avcC, List<AccessUnit> units) {}

    /** One CarPlay session's media path: the engine's sink (the tee) and the browser tap inside it. */
    record Session(int number, MediaSink engineSink, WebMediaSink tap) {}

    private static final Object OUT = new Object();
    private static volatile Session session;
    private static final AtomicBoolean keyRequested = new AtomicBoolean(false);
    private static final AtomicReference<BrowserViewport> viewport = new AtomicReference<>();
    private static volatile boolean reportViewport = true;

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        Path page = Paths.get(options.getOrDefault("page", "site/play")).toAbsolutePath().normalize();
        String code = options.getOrDefault("code", "271828");
        int width = Integer.parseInt(options.getOrDefault("width", "1280"));
        int height = Integer.parseInt(options.getOrDefault("height", "720"));
        int fps = Integer.parseInt(options.getOrDefault("fps", "30"));
        Clip clip = options.containsKey("clip") ? readClip(Files.readAllBytes(Paths.get(options.get("clip")))) : handMadeClip(fps);
        emit("clip", "units", clip.units().size(), "keyUnits", clip.units().stream().filter(AccessUnit::key).count(),
            "source", options.containsKey("clip") ? "file" : "hand-made");

        WebVideoHub hub = new WebVideoHub(Executors.newSingleThreadExecutor(), System::nanoTime, message -> {
            emit("log", "source", "hub", "message", message);
            return Unit.INSTANCE;
        });
        BrowserLinkServer.Callbacks callbacks = new BrowserLinkServer.Callbacks() {
            @Override public String code() { return code; }

            // Called once per /control request, so it doubles as the heartbeat counter.
            @Override public BrowserLinkStatus status() {
                WebVideoHub.StreamInfo info = hub.info();
                emit("control");
                BrowserViewport last = reportViewport ? viewport.get() : null;
                return new BrowserLinkStatus(
                    info != null ? BrowserLinkStatus.State.STREAMING : BrowserLinkStatus.State.IDLE,
                    info == null ? null : info.getCodec(),
                    info == null ? null : new BrowserSize(info.getWidth(), info.getHeight()),
                    last == null ? null : last.getSize(),
                    true,
                    Map.of("harness", "RealPhone"));
            }

            @Override public boolean onTouch(List<AirPlayContact> contacts) {
                List<Object> points = new ArrayList<>();
                for (AirPlayContact contact : contacts) {
                    points.add(List.of(contact.getId(), contact.getX(), contact.getY(), contact.getDown()));
                }
                emit("touch", "contacts", points);
                return true;
            }

            @Override public void onViewport(BrowserViewport next) {
                viewport.set(next);
                emit("viewport", "w", next.getWidth(), "h", next.getHeight(), "cw", next.getCssWidth(),
                    "ch", next.getCssHeight(), "dpr", next.getDevicePixelRatio());
            }

            @Override public void onFit() { emit("fit"); }

            @Override public void onKey(String name) { emit("key", "name", name); }

            @Override public void onBrowserStats(Map<String, ?> stats) { emit("stats", "values", stats); }

            @Override public void log(String message) { emit("log", "source", "server", "message", message); }
        };
        BrowserLinkServer server = new BrowserLinkServer(hub, callbacks, options.getOrDefault("bind", "0.0.0.0"),
            Integer.parseInt(options.getOrDefault("port", "0")), () -> System.nanoTime() / 1_000_000, name -> {
                Path file = page.resolve(name).normalize();
                boolean inside = page.equals(file.getParent());
                emit("asset", "name", name, "inside", inside);
                if (!inside || !Files.isRegularFile(file)) return null;
                try {
                    return Files.readAllBytes(file);
                } catch (Exception error) {
                    return null;
                }
            });
        // start() returns kotlin.Result<Int>, an inline class, so its JVM name is mangled.
        BrowserLinkServer.class.getMethod("start-d1pmJ48").invoke(server);
        if (server.getLocalPort() <= 0) {
            emit("failed", "reason", "the server did not start");
            System.exit(1);
        }

        startSession(hub, clip, width, height, fps);
        ScheduledExecutorService feeder = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger next = new AtomicInteger();
        long frameNanos = 1_000_000_000L / fps;
        long[] count = {0};
        feeder.scheduleAtFixedRate(() -> {
            Session current = session;
            if (current == null) return;
            int index = next.get();
            if (keyRequested.getAndSet(false)) {
                // The iPhone answers with a byte-identical config and an IDR, which keeps the epoch.
                current.engineSink().onVideoConfig(MAIN_SCREEN, clip.avcC());
                for (int step = 0; step < clip.units().size(); step++) {
                    int candidate = (index + step) % clip.units().size();
                    if (clip.units().get(candidate).key()) {
                        index = candidate;
                        break;
                    }
                }
            }
            AccessUnit unit = clip.units().get(index);
            next.set((index + 1) % clip.units().size());
            current.engineSink().onVideoFrame(MAIN_SCREEN, unit.bytes(), ++count[0] * frameNanos, System.nanoTime());
        }, frameNanos, frameNanos, TimeUnit.NANOSECONDS);
        emit("listening", "port", server.getLocalPort(), "page", page.toString());

        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line; (line = input.readLine()) != null; ) {
            switch (line.trim()) {
                case "restart" -> {
                    // restartCarPlay: TeslaBrowserLink.releaseTap closes the old tap at once; the old stack closes later.
                    Session retired = session;
                    retired.tap().close();
                    next.set(0);
                    startSession(hub, clip, width, height, fps);
                    // A retired session whose screen comes up late must not take the hub from the new one.
                    retired.engineSink().onScreenStreamActive(MAIN_SCREEN, true);
                    retired.engineSink().onVideoCodec(MAIN_SCREEN, VideoCodec.H264);
                    retired.engineSink().onVideoConfig(MAIN_SCREEN, clip.avcC());
                    emit("restarted", "session", session.number());
                }
                case "fit-on" -> reportViewport = true;
                case "fit-off" -> reportViewport = false;
                case "quit" -> {
                    feeder.shutdownNow();
                    server.stop();
                    emit("stopped");
                    System.exit(0);
                }
                default -> emit("unknown-command", "line", line);
            }
        }
        feeder.shutdownNow();
        server.stop();
        System.exit(0);
    }

    private static final AtomicInteger sessions = new AtomicInteger();

    /** A new session's engine sink, as CarPlayMediaEngine drives it: handlers first, then the screen, codec and config. */
    private static void startSession(WebVideoHub hub, Clip clip, int width, int height, int fps) {
        int number = sessions.incrementAndGet();
        WebMediaSink tap = new WebMediaSink(hub, width, height, fps);
        MediaSink androidSink = new MediaSink() {}; // stands in for AndroidMediaSink, which needs Android
        MediaSink tee = new VideoTeeMediaSink(androidSink, tap, error -> {
            emit("tap-error", "session", number, "error", error.getClass().getSimpleName());
            return Unit.INSTANCE;
        });
        tee.setVideoRecoveryHandler(MAIN_SCREEN, () -> {
            emit("keyframe-request", "session", number);
            if (session != null && session.number() == number) keyRequested.set(true);
            return Unit.INSTANCE;
        });
        tee.setVideoDiagnosticHandler(MAIN_SCREEN, message -> {
            emit("diagnostic", "session", number, "message", message);
            return Unit.INSTANCE;
        });
        tee.onScreenStreamActive(MAIN_SCREEN, true);
        tee.onVideoCodec(MAIN_SCREEN, VideoCodec.H264);
        tee.onVideoConfig(MAIN_SCREEN, clip.avcC());
        session = new Session(number, tee, tap);
    }

    /** Splits an x264 Annex-B stream written with aud=1 into access units, and builds the avcC record from its SPS/PPS. */
    static Clip readClip(byte[] stream) {
        List<int[]> nals = new ArrayList<>(); // {payload start, payload end, type}
        int start = -1;
        for (int i = 0; i + 3 <= stream.length; i++) {
            boolean three = i + 3 <= stream.length && stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1;
            if (!three) continue;
            if (start >= 0) {
                int end = i;
                while (end > start && stream[end - 1] == 0) end--;
                nals.add(new int[] {start, end, stream[start] & 0x1f});
            }
            start = i + 3;
            i += 2;
        }
        if (start >= 0 && start < stream.length) nals.add(new int[] {start, stream.length, stream[start] & 0x1f});
        byte[] sps = null, pps = null;
        List<AccessUnit> units = new ArrayList<>();
        ByteArrayOutputStream unit = new ByteArrayOutputStream();
        boolean key = false;
        for (int[] nal : nals) {
            int type = nal[2];
            if (type == 9) {
                if (unit.size() > 0) units.add(new AccessUnit(unit.toByteArray(), key));
                unit.reset();
                key = false;
                continue;
            }
            byte[] bytes = java.util.Arrays.copyOfRange(stream, nal[0], nal[1]);
            if (type == 7) {
                if (sps == null) sps = bytes;
                continue;
            }
            if (type == 8) {
                if (pps == null) pps = bytes;
                continue;
            }
            if (type == 6) continue;
            if (type == 5) key = true;
            unit.writeBytes(new byte[] {0, 0, 0, 1});
            unit.writeBytes(bytes);
        }
        if (unit.size() > 0) units.add(new AccessUnit(unit.toByteArray(), key));
        if (sps == null || pps == null || units.isEmpty() || !units.get(0).key()) {
            throw new IllegalArgumentException("the clip needs SPS, PPS and access units split by AUDs, starting with an IDR");
        }
        return new Clip(avcC(sps, pps), units);
    }

    /** Constrained Baseline 1280x720 SPS/PPS (from x264) and fake slices: valid records, not a decodable picture. */
    static Clip handMadeClip(int fps) {
        byte[] sps = hex("6742c01fda014016ec044000000300400000 0f23c60ca8");
        byte[] pps = hex("68ce3c80");
        List<AccessUnit> units = new ArrayList<>();
        for (int i = 0; i < fps; i++) {
            boolean key = i == 0;
            byte[] slice = new byte[200];
            slice[0] = (byte) (key ? 0x65 : 0x41);
            for (int b = 1; b < slice.length; b++) slice[b] = (byte) (0x5a ^ b ^ i);
            ByteArrayOutputStream unit = new ByteArrayOutputStream();
            unit.writeBytes(new byte[] {0, 0, 0, 1});
            unit.writeBytes(slice);
            units.add(new AccessUnit(unit.toByteArray(), key));
        }
        return new Clip(avcC(sps, pps), units);
    }

    static byte[] avcC(byte[] sps, byte[] pps) {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.writeBytes(new byte[] {1, sps[1], sps[2], sps[3], (byte) 0xff, (byte) 0xe1});
        record.writeBytes(new byte[] {(byte) (sps.length >> 8), (byte) sps.length});
        record.writeBytes(sps);
        record.writeBytes(new byte[] {1, (byte) (pps.length >> 8), (byte) pps.length});
        record.writeBytes(pps);
        return record.toByteArray();
    }

    static byte[] hex(String text) {
        String digits = text.replace(" ", "");
        byte[] bytes = new byte[digits.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(digits.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }

    /** Prints {"event": name, key: value, …} on one line. */
    static void emit(String name, Object... fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", name);
        event.put("at", System.currentTimeMillis());
        for (int i = 0; i + 1 < fields.length; i += 2) event.put(String.valueOf(fields[i]), fields[i + 1]);
        StringBuilder text = new StringBuilder();
        json(text, event);
        synchronized (OUT) {
            System.out.println(text);
            System.out.flush();
        }
    }

    static void json(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Number number) {
            double d = number.doubleValue();
            out.append(Double.isFinite(d) ? String.valueOf(d) : "null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                json(out, String.valueOf(entry.getKey()));
                out.append(':');
                json(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(',');
                first = false;
                json(out, item);
            }
            out.append(']');
        } else {
            out.append('"');
            for (char c : value.toString().toCharArray()) {
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                else out.append(c);
            }
            out.append('"');
        }
    }
}
