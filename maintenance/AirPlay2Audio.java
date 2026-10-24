package wtf.nanoka.airplay.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import wtf.nanoka.airplay.lib.NetworkInterfaceSelector;

/** Owns the optional Windows AirPlay 2 audio process, never the mirror receiver. */
@Component
public final class AirPlay2Audio implements DisposableBean {
    private static volatile AirPlay2Audio instance;
    private static volatile String state = "stopped";
    private static volatile String detail = "";
    private final Environment environment;
    private Process process;
    private volatile boolean closed;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final CountDownLatch shutdownComplete = new CountDownLatch(1);
    private final Path data = Path.of(System.getProperty("user.home"), ".java-airplay", "airplay2");
    private final Path status = data.resolve("status-" + ProcessHandle.current().pid() + ".properties");
    private final Path stop = data.resolve("stop-" + ProcessHandle.current().pid());

    public AirPlay2Audio(Environment environment) { this.environment = environment; instance = this; }

    public static boolean enabled() {
        Properties preferences = new Properties();
        Path path = Path.of(System.getProperty("user.home"), ".java-airplay", "desktop.properties");
        if (Files.exists(path)) try (Reader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            preferences.load(in);
        } catch (IOException e) { System.err.println("AirPlay 2 preferences: " + e.getMessage()); }
        return Boolean.parseBoolean(preferences.getProperty("airplay2.enabled", "true"));
    }

    public static String state() { return state; }
    public static String detail() { return detail; }

    public static void setEnabled(boolean value) throws IOException {
        Path file = Path.of(System.getProperty("user.home"), ".java-airplay", "desktop.properties");
        Properties properties = new Properties();
        if (Files.exists(file)) try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(in); }
        properties.setProperty("airplay2.enabled", Boolean.toString(value));
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { properties.store(out, "Desktop preferences"); }
        AirPlay2Audio owner = instance;
        if (owner != null) owner.reconcile();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        if (!environment.getProperty("java-airplay.validation", Boolean.class, false)) reconcile();
    }

    private void reconcile() {
        if (closed) return;
        long request = generation.incrementAndGet();
        Thread.ofPlatform().daemon(true).name("airplay2-lifecycle").start(() -> update(request));
    }

    private synchronized void update(long request) {
        if (closed || request != generation.get()) return;
        stopProcess();
        if (!enabled()) { state = "disabled"; detail = ""; return; }
        state = "starting";
        try {
            Path runtime = Path.of(environment.getProperty("airplay.airplay2.runtime", ".runtime/airplay2")).toAbsolutePath();
            Path executable = runtime.resolve("pythonw.exe");
            Path entry = runtime.resolve("receiver/desktop_receiver.py");
            if (!Files.isRegularFile(executable) || !Files.isRegularFile(entry))
                throw new IOException("AirPlay 2 runtime missing: " + runtime);
            var network = NetworkInterfaceSelector.resolve(environment.getProperty("airplay.networkInterface", "auto"));
            if (network.chosen() == null) throw new IOException("No AirPlay network interface available");
            String address = network.chosen().ipv4();
            String name = environment.getProperty("airplay.serverName", "airplay") + " Music";
            int port = environment.getProperty("airplay.airplay2.port", Integer.class, 7001);
            if (port < 1024 || port > 65535) throw new IOException("Invalid AirPlay 2 audio port");
            Files.createDirectories(data);
            Files.deleteIfExists(status); Files.deleteIfExists(stop);
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), entry.toString(),
                    "--parent", Long.toString(ProcessHandle.current().pid()), "--address", address,
                    "--name", name, "--port", Integer.toString(port), "--state", data.toString(),
                    "--status", status.toString(), "--stop", stop.toString());
            builder.directory(data.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("PYTHONUNBUFFERED", "1");
            process = builder.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (!closed && request == generation.get() && process.isAlive() && !Files.exists(status) && System.nanoTime() < deadline) Thread.sleep(100);
            if (closed || request != generation.get()) { stopProcess(); return; }
            if (!process.isAlive()) throw new IOException("Audio receiver exited (" + process.exitValue() + "); see " + data.resolve("receiver.log"));
            if (!Files.exists(status)) throw new IOException("Audio receiver startup timed out; see " + data.resolve("receiver.log"));
            state = "ready"; detail = name + " · " + address + ":" + port;
            Process started = process;
            started.onExit().thenRun(() -> {
                synchronized (this) {
                    if (process == started && !closed) {
                        process = null; state = "error";
                        detail = "Audio receiver exited (" + started.exitValue() + "); " + data.resolve("receiver.log");
                    }
                }
            });
            System.out.println("AirPlay 2 audio ready (experimental, precise sync unavailable): " + detail);
        } catch (Exception e) {
            stopProcess(); state = "error"; detail = e.getMessage();
            System.err.println("AirPlay 2 audio: " + detail);
        }
    }

    private void stopProcess() {
        Process running = process; process = null;
        if (running != null) try {
            Files.createDirectories(data); Files.writeString(stop, "stop");
            if (!running.waitFor(6, TimeUnit.SECONDS)) {
                // The Python process owns a Windows job: terminating it also
                // terminates audio, event and RTCP descendants created by it.
                running.destroyForcibly(); running.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (IOException e) {
            running.destroyForcibly();
        } catch (InterruptedException e) {
            running.destroyForcibly(); Thread.currentThread().interrupt();
        }
        try { Files.deleteIfExists(status); Files.deleteIfExists(stop); } catch (IOException ignored) { }
    }

    public static void requestShutdown() {
        AirPlay2Audio owner = instance;
        if (owner != null) owner.beginShutdown();
    }

    private void beginShutdown() {
        closed = true; generation.incrementAndGet();
        if (!shutdownStarted.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon(true).name("airplay2-stop").start(() -> {
            try {
                synchronized (this) { stopProcess(); state = "stopped"; instance = null; }
            } finally { shutdownComplete.countDown(); }
        });
    }

    @Override public void destroy() {
        beginShutdown();
        try { shutdownComplete.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
