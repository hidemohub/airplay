package wtf.nanoka.airplay.app;

import java.awt.SystemTray;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerSwing;
import wtf.nanoka.airplay.player.gstreamer.ui.WindowPinControls;

/** Lifecycle repair for the binary-only Windows 1.1.2 release. */
@Component
public final class DesktopLifecycle {
    public static void normalizeSettings(java.util.Properties settings) {
        String name = settings.getProperty("airplay.serverName", "");
        if (name.matches("(?i)Java\\s+AirPlay")) settings.setProperty("airplay.serverName", "airplay");
    }
    private static final AtomicBoolean EXITING = new AtomicBoolean();
    private static final AtomicBoolean DEADLINE_STARTED = new AtomicBoolean();

    @EventListener
    public void started(ApplicationStartedEvent event) throws Exception {
        var players = event.getApplicationContext().getBeansOfType(GstPlayerSwing.class);
        for (var player : players.values()) {
            Field windowField = GstPlayerSwing.class.getDeclaredField("window");
            windowField.setAccessible(true);
            Object window = windowField.get(player);
            Field frameField = window.getClass().getDeclaredField("frame");
            frameField.setAccessible(true);
            JFrame frame = (JFrame) frameField.get(window);
            SwingUtilities.invokeAndWait(() -> {
                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                frame.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent e) {
                        if (WindowPinControls.hideOnClose() && SystemTray.isSupported()
                                && SystemTray.getSystemTray().getTrayIcons().length > 0) {
                            WindowPinControls.prepareHide(frame);
                            frame.setVisible(false);
                        } else {
                            requestExit(event.getApplicationContext());
                        }
                    }
                });
            });
        }
    }

    @EventListener
    public void closing(ContextClosedEvent event) {
        startDeadline();
    }

    private static void startDeadline() {
        if (!DEADLINE_STARTED.compareAndSet(false, true)) return;
        Thread.ofPlatform().daemon(true).name("airplay-exit-deadline").start(() -> {
            try { Thread.sleep(30000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            System.err.println("AirPlay shutdown exceeded 30 seconds; terminating this JVM.");
            Runtime.getRuntime().halt(1);
        });
    }

    public static void requestExit(ApplicationContext context) {
        if (!EXITING.compareAndSet(false, true)) return;
        startDeadline();
        // Do not bring the main window back when native playback is detached during shutdown.
        Runnable hide = () -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JFrame frame) {
                    frame.getRootPane().putClientProperty("airplay.exiting", true);
                    WindowPinControls.prepareHide(frame);
                }
                window.setVisible(false);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) hide.run(); else SwingUtilities.invokeLater(hide);
        // Withdraw the audio service while Spring closes mirror discovery and playback.
        AirPlay2Audio.requestShutdown();
        // Native playback and network shutdown must not block the Swing event thread.
        Thread.ofPlatform().name("airplay-desktop-exit").start(() -> {
            int code = 0;
            try {
                ((ConfigurableApplicationContext) context).close();
            } catch (Throwable error) {
                error.printStackTrace();
                code = 1;
            } finally {
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        for (Window window : Window.getWindows()) window.dispose();
                        if (SystemTray.isSupported()) {
                            SystemTray tray = SystemTray.getSystemTray();
                            for (var icon : tray.getTrayIcons()) tray.remove(icon);
                        }
                    });
                } catch (Exception error) {
                    error.printStackTrace();
                    code = 1;
                }
                System.exit(code);
            }
        });
    }
}
