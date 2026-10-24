import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import wtf.nanoka.airplay.app.*;

/** Measures the shipped classes; uses an isolated user.home supplied by the runner. */
public class VerifyLifecycle {
    public static void main(String[] args) throws Exception {
        long begin = System.nanoTime();
        AtomicBoolean visible = new AtomicBoolean();
        Thread.ofPlatform().daemon(true).start(() -> {
            while (!visible.get()) {
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        for (Window window : Window.getWindows()) if (window.isShowing() && visible.compareAndSet(false, true))
                            System.out.println("WINDOW_VISIBLE_MS=" + (System.nanoTime()-begin)/1_000_000);
                    });
                    Thread.sleep(50);
                } catch (Exception e) { return; }
            }
        });
        if (args.length > 0 && args[0].equals("sample")) Thread.ofPlatform().daemon(true).start(() -> {
            while (!visible.get()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                Thread.getAllStackTraces().forEach((thread, stack) -> {
                    if (thread.getName().equals("main") || thread.getName().startsWith("AWT-EventQueue")) {
                        System.out.println("SAMPLE " + thread.getName());
                        for (int i=0;i<Math.min(stack.length,12);i++) System.out.println(stack[i]);
                    }
                });
            }
        });
        var context = new SpringApplicationBuilder(PlayerApp.class).web(WebApplicationType.NONE).headless(false)
            .run("--java-airplay.validation=" + !(args.length > 1), "--debug=false",
                "--airplay.networkInterface=" + (args.length > 1 ? args[1] : "auto"), "--airplay.airplay2.port=7003");
        System.out.println("CONTEXT_READY_MS="+(System.nanoTime()-begin)/1_000_000);
        if (!visible.get()) throw new AssertionError("Window not visible");
        if (args.length > 1) {
            long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (!AirPlay2Audio.state().equals("ready") && !AirPlay2Audio.state().equals("error") && System.nanoTime()<deadline) Thread.sleep(50);
            if (!AirPlay2Audio.state().equals("ready")) throw new AssertionError(AirPlay2Audio.detail());
            System.out.println("AUDIO_READY=true");
        }
        long[] children=ProcessHandle.current().descendants().mapToLong(ProcessHandle::pid).toArray();
        java.nio.file.Files.writeString(java.nio.file.Path.of("maintenance/perf/child-pids.txt"),java.util.Arrays.toString(children));
        long end = System.nanoTime();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("SHUTDOWN_MS="+(System.nanoTime()-end)/1_000_000)));
        SwingUtilities.invokeAndWait(() -> DesktopLifecycle.requestExit(context));
        SwingUtilities.invokeAndWait(() -> {
            System.out.println("EXIT_EDT_RETURN_MS="+(System.nanoTime()-end)/1_000_000);
            boolean showing = java.util.Arrays.stream(Window.getWindows()).anyMatch(Window::isShowing);
            System.out.println("WINDOW_SHOWING_AFTER_EXIT="+showing);
        });
    }
}
