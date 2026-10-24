import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import javax.swing.*;
import java.nio.file.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import wtf.nanoka.airplay.app.PlayerApp;
import wtf.nanoka.airplay.app.config.UserSettingsPropertySource;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerSwing;
import wtf.nanoka.airplay.player.gstreamer.ui.WindowPinControls;

public class VerifyWindows {
    static Object get(Object model, String name) throws Exception {
        Field field = model.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(model);
    }
    static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { Component found = find(nested, name); if (found != null) return found; }
        }
        return null;
    }
    static AbstractButton button(JFrame frame, String name) { return (AbstractButton) find(frame, "airplay." + name); }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void flush() throws Exception { SwingUtilities.invokeAndWait(() -> {}); }
    static void preview(JFrame frame, String name) throws Exception {
        var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); frame.printAll(g); g.dispose();
        javax.imageio.ImageIO.write(image, "png", new java.io.File("maintenance/" + name + ".png"));
    }
    static java.awt.image.BufferedImage render(JComponent component, java.awt.image.BufferedImage image) {
        if (image == null) image = new java.awt.image.BufferedImage(component.getWidth(), component.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); component.printAll(g); g.dispose(); return image;
    }
    static boolean same(java.awt.image.BufferedImage left, java.awt.image.BufferedImage right) {
        return java.util.Arrays.equals(left.getRGB(0, 0, left.getWidth(), left.getHeight(), null, 0, left.getWidth()),
            right.getRGB(0, 0, right.getWidth(), right.getHeight(), null, 0, right.getWidth()));
    }
    static void cleanRepaint(JComponent component) {
        var reference = render(component, null);
        var repeated = render(component, null);
        for (int i = 0; i < 30; i++) render(component, repeated);
        check(same(reference, repeated), "Repaint accumulated old pixels: " + component.getName());
    }
    static void verifyPainting(JFrame frame, Object model) {
        try {
            JComponent status = (JComponent) find(frame, "airplay.connectionStatus");
            Object old = get(model, "statusChip"); Object state = get(old, "state");
            Method setState = old.getClass().getDeclaredMethod("setState", state.getClass()); setState.setAccessible(true);
            var reused = render(status, null);
            for (Object next : state.getClass().getEnumConstants()) {
                setState.invoke(old, next); render(status, reused);
                check(same(reused, render(status, null)), "Connection status left old text behind");
                cleanRepaint(status);
            }
            setState.invoke(old, state);
            cleanRepaint((JComponent) find(frame, "airplay.titlebar"));
            check(button(frame, "pin").getX() < button(frame, "fullscreen").getX(), "Pin order");
            var sheet = new java.awt.image.BufferedImage(126, 72, java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics(); int x = 0;
            for (String name : new String[]{"pin", "fullscreen", "maximize"}) {
                JComponent control = (JComponent) button(frame, name);
                var inactive = render(control, null); cleanRepaint(control);
                button(frame, name).doClick();
                var active = render(control, null);
                check(!same(inactive, active), "State icon unchanged: " + name);
                render(control, inactive);
                check(same(inactive, active), "State switch left previous icon behind: " + name);
                cleanRepaint(control);
                g.drawImage(renderInactive(frame, name), x, 0, null); g.drawImage(active, x, 36, null);
                x += 42;
            }
            g.dispose(); javax.imageio.ImageIO.write(sheet, "png", new java.io.File("maintenance/window-icon-states.png"));
            System.out.println("VERIFY: repeated paint, connection text transitions, clean icon transitions and pin order passed");
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static java.awt.image.BufferedImage renderInactive(JFrame frame, String name) {
        button(frame, name).doClick(); return render((JComponent) button(frame, name), null);
    }
    static void controls(JFrame frame) {
        check(frame.isUndecorated(), "Custom titlebar missing");
        Container parent = button(frame, "close").getParent();
        for (String name : new String[]{"pin", "fullscreen", "minimize", "maximize"}) {
            check(button(frame, name) != null && button(frame, name).getParent() == parent, "Control group: " + name);
        }
        check(!frame.isAlwaysOnTop(), "Window starts pinned");
        button(frame, "pin").doClick(); check(frame.isAlwaysOnTop(), "Pin on");
        button(frame, "pin").doClick(); check(!frame.isAlwaysOnTop(), "Pin off");
        Rectangle before = frame.getBounds();
        button(frame, "fullscreen").doClick();
        check(frame.getGraphicsConfiguration().getDevice().getFullScreenWindow() == frame, "Full screen did not activate");
        frame.getRootPane().getActionMap().get("airplay.exitFull").actionPerformed(new ActionEvent(frame, 0, "escape"));
        check(frame.getGraphicsConfiguration().getDevice().getFullScreenWindow() != frame, "Escape did not exit full screen");
        check(frame.getBounds().equals(before), "Normal bounds were not restored");
        button(frame, "maximize").doClick();
        check((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH, "Maximize failed");
        button(frame, "maximize").doClick();
        check((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0, "Restore failed");
        button(frame, "minimize").doClick();
        check((frame.getExtendedState() & JFrame.ICONIFIED) != 0, "Minimize failed");
        frame.setExtendedState(JFrame.NORMAL); frame.setBounds(before);
    }
    public static void main(String[] args) throws Exception {
        var context = new SpringApplicationBuilder(PlayerApp.class).web(WebApplicationType.NONE).headless(false)
            .initializers(new UserSettingsPropertySource()).run("--java-airplay.validation=true");
        var player = context.getBean(GstPlayerSwing.class);
        Object model = get(player, "window"); JFrame main = (JFrame) get(model, "frame");
        Object detachedModel = get(model, "detachedVideoWindow"); JFrame detached = (JFrame) get(detachedModel, "frame");
        SwingUtilities.invokeAndWait(() -> {
            controls(main);
            verifyPainting(main, model);
            check(!((JComponent) uncheckedGet(model, "fullscreenButton")).isVisible(), "Old full screen button remains");
            check(find(main, "airplay.connectionStatus") != null, "Connection indicator missing");
            try { preview(main, "window-controls-main"); } catch (Exception e) { throw new RuntimeException(e); }
            try {
                Method section = model.getClass().getDeclaredMethod("selectSection", String.class); section.setAccessible(true);
                section.invoke(model, "settings"); main.validate(); preview(main, "window-controls-settings");
                section.invoke(model, "receiver"); main.validate();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        player.showDetachedVideo(); flush(); flush();
        SwingUtilities.invokeAndWait(() -> {
            check(!main.isVisible() && detached.isVisible(), "Opening detached window must hide main");
            controls(detached);
            try { preview(detached, "window-controls-detached"); } catch (Exception e) { throw new RuntimeException(e); }
            player.toggleVideoFullscreen();
            check(detached.getGraphicsConfiguration().getDevice().getFullScreenWindow() == detached, "Tray full screen must use visible detached window");
            button(detached, "close").doClick();
        });
        flush(); flush();
        SwingUtilities.invokeAndWait(() -> check(main.isVisible() && !detached.isVisible(), "Closing detached must restore main"));
        JComboBox<?> behavior = (JComboBox<?>) find(main, "airplay.closeBehavior");
        SwingUtilities.invokeAndWait(() -> behavior.setSelectedIndex(1));
        check(WindowPinControls.hideOnClose(), "Tray selection not persisted");
        check(SystemTray.isSupported() && SystemTray.getSystemTray().getTrayIcons().length > 0, "Tray icon unavailable for integration test");
        SwingUtilities.invokeAndWait(() -> button(main, "close").doClick()); flush();
        check(!main.isVisible() && context.isActive(), "Hide to tray closed service");
        player.showWindow(); flush();
        check(main.isVisible(), "Tray restore failed");
        SwingUtilities.invokeAndWait(() -> {
            behavior.setSelectedIndex(0);
            check(!WindowPinControls.hideOnClose(), "Exit selection not persisted");
            System.out.println("VERIFY: both titlebars, full screen and Escape, pin, detached hide/restore, tray hide/restore and settings persistence passed");
            button(main, "close").doClick();
        });
        Thread.sleep(15000); throw new AssertionError("Application did not exit");
    }
    static Object uncheckedGet(Object model, String name) {
        try { return get(model, name); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
