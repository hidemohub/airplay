import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import javax.swing.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import wtf.nanoka.airplay.app.PlayerApp;
import wtf.nanoka.airplay.app.config.UserSettingsPropertySource;
import wtf.nanoka.airplay.app.menu.SystemTrayMenu;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerSwing;
import wtf.nanoka.airplay.player.gstreamer.ui.I18n;

/** Runs the real application and closes it through the selected desktop action. */
public class VerifyDesktop {
    public static void main(String[] args) throws Exception {
        var context = new SpringApplicationBuilder(PlayerApp.class)
            .web(WebApplicationType.NONE).headless(false)
            .initializers(new UserSettingsPropertySource()).run(
                "--java-airplay.validation=" + !(args.length > 1 && args[1].equals("network")));
        var player = context.getBean(GstPlayerSwing.class);
        Field wf = GstPlayerSwing.class.getDeclaredField("window");
        wf.setAccessible(true);
        Object window = wf.get(player);
        Field ff = window.getClass().getDeclaredField("frame");
        ff.setAccessible(true);
        JFrame frame = (JFrame) ff.get(window);
        if (frame.getDefaultCloseOperation() != JFrame.DO_NOTHING_ON_CLOSE)
            throw new AssertionError("Main window must use lifecycle cleanup");
        if (args[0].equals("pin")) {
            SwingUtilities.invokeAndWait(() -> verifyPin(frame, window));
            System.out.println("VERIFY: pin default, both toggle directions, settings synchronization and language passed");
        }
        player.showDetachedVideo();
        SwingUtilities.invokeAndWait(() -> {});
        System.out.println("VERIFY: application ready, detached video opened, action=" + args[0]);
        if (args[0].equals("tray")) {
            var tray = context.getBean(SystemTrayMenu.class);
            Method exit = SystemTrayMenu.class.getDeclaredMethod("lambda$initializePopupMenu$4", org.springframework.context.ApplicationContext.class, ActionEvent.class);
            exit.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try { exit.invoke(tray, context, new ActionEvent(frame, 0, "exit")); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
        } else {
            SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        }
        Thread.sleep(15000);
        throw new AssertionError("Application did not exit in 15 seconds");
    }

    private static JToggleButton findPin(Container parent) {
        for (Component child : parent.getComponents()) {
            if ("airplay.pin".equals(child.getName())) return (JToggleButton) child;
            if (child instanceof Container container) {
                JToggleButton found = findPin(container);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void verifyPin(JFrame frame, Object window) {
        try {
            JToggleButton pin = findPin(frame);
            if (pin == null || !pin.isShowing()) throw new AssertionError("Header pin not visible");
            if (frame.isAlwaysOnTop() || pin.isSelected()) throw new AssertionError("Must start unpinned");
            Field toggleField = window.getClass().getDeclaredField("alwaysOnTopToggle");
            toggleField.setAccessible(true);
            AbstractButton settings = (AbstractButton) toggleField.get(window);
            pin.doClick();
            if (!frame.isAlwaysOnTop() || !settings.isSelected()) throw new AssertionError("Pin on failed");
            pin.doClick();
            if (frame.isAlwaysOnTop() || settings.isSelected()) throw new AssertionError("Pin off failed");
            settings.doClick();
            if (!frame.isAlwaysOnTop() || !pin.isSelected()) throw new AssertionError("Settings on failed");
            settings.doClick();
            if (frame.isAlwaysOnTop() || pin.isSelected()) throw new AssertionError("Settings off failed");
            Field languageField = window.getClass().getDeclaredField("i18n");
            languageField.setAccessible(true);
            I18n i18n = (I18n) languageField.get(window);
            I18n.Language previous = i18n.language();
            i18n.setLanguage(I18n.Language.ENGLISH);
            if (!pin.getToolTipText().equals("Pin")) throw new AssertionError("English pin label");
            i18n.setLanguage(I18n.Language.CHINESE);
            if (!pin.getToolTipText().equals("置顶")) throw new AssertionError("Chinese pin label");
            frame.validate();
            var preview = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = preview.createGraphics();
            frame.printAll(graphics);
            graphics.dispose();
            javax.imageio.ImageIO.write(preview, "png", new java.io.File("maintenance/pin-preview.png"));
            frame.setSize(frame.getMinimumSize());
            frame.validate();
            if (!pin.isShowing() || pin.getVisibleRect().width < pin.getWidth()) throw new AssertionError("Pin clipped at minimum width");
            i18n.setLanguage(previous);
        } catch (Exception error) { throw new RuntimeException(error); }
    }
}
