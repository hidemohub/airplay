package wtf.nanoka.airplay.player.gstreamer.ui;

import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.io.*;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.prefs.Preferences;
import javax.swing.*;

/** Shared window chrome and desktop preferences for the binary release. */
public final class WindowPinControls {
    private static final String CHROME = "airplay.chrome";
    private static final String BODY = "airplay.body";
    private WindowPinControls() {}
    // Opaque Swing components must replace every background pixel before repainting.
    // Repeatedly blending translucent glass over the previous frame leaves old glyphs behind.
    private static void clearBackground(Graphics2D g, int width, int height, ThemeManager.ThemePalette palette) {
        g.setComposite(AlphaComposite.Src);
        g.setColor(new Color(palette.backgroundStart().getRGB()));
        g.fillRect(0, 0, width, height);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(palette.glassStrong());
        g.fillRect(0, 0, width, height);
    }
    public static boolean initialPreference(Preferences prefs, String key, boolean fallback) {
        return key.equals("always-on-top") ? false : prefs.getBoolean(key, fallback);
    }
    private static Object field(Object model, String name) {
        try {
            Field f = model.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(model);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e); }
    }
    private static Object call(Object model, String name, Class<?>[] types, Object... args) {
        try {
            Method m = model.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(model, args);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(name, e); }
    }
    private static void edt(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) task.run(); else SwingUtilities.invokeLater(task);
    }
    public static Container body(JFrame frame) {
        Container body = (Container) frame.getRootPane().getClientProperty(BODY);
        return body == null ? frame.getContentPane() : body;
    }
    private static JFrame frame(Object model) { return (JFrame) field(model, "frame"); }
    private static Chrome chrome(JFrame frame) { return (Chrome) frame.getRootPane().getClientProperty(CHROME); }

    public static JComponent decorateHeader(JComponent header, Object model) {
        BorderLayout layout = (BorderLayout) header.getLayout();
        JComponent old = (JComponent) layout.getLayoutComponent(BorderLayout.EAST);
        if (old != null) {
            header.remove(old);
            I18n i18n = (I18n) field(model, "i18n");
            ThemeManager theme = (ThemeManager) field(model, "themeManager");
            JComponent status = new JPanel() {
                { setPreferredSize(new Dimension(122, 32)); setName("airplay.connectionStatus"); }
                @Override protected void paintComponent(Graphics graphics) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    try {
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        var palette = theme.palette();
                        clearBackground(g, getWidth(), getHeight(), palette);
                        Object state = field(old, "state");
                        String key = (String) call(state, "labelKey", new Class<?>[0]);
                        g.setColor(palette.control()); g.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
                        g.setColor(state.toString().equals("CONNECTING") ? palette.warning() : palette.success());
                        g.fillOval(12, 12, 8, 8);
                        g.setColor(palette.textPrimary()); g.setFont(new Font("Microsoft YaHei UI", Font.BOLD, 12));
                        g.drawString(i18n.tr(key), 29, 21);
                        getAccessibleContext().setAccessibleName(i18n.tr(key));
                    } finally { g.dispose(); }
                }
            };
            old.addPropertyChangeListener(event -> status.repaint());
            i18n.addLanguageChangeListener(status::repaint); theme.addListener(status::repaint);
            header.add(status, BorderLayout.EAST);
        }
        return header;
    }

    private static Path preferencesFile() {
        return Path.of(System.getProperty("user.home"), ".java-airplay", "desktop.properties");
    }
    public static boolean hideOnClose() {
        Properties prefs = new Properties();
        if (Files.exists(preferencesFile())) {
            try (InputStream in = Files.newInputStream(preferencesFile())) { prefs.load(in); }
            catch (IOException e) { System.err.println("Unable to read desktop preferences: " + e); }
        }
        return Boolean.parseBoolean(prefs.getProperty("close-to-tray", "false"));
    }
    private static void saveCloseBehavior(boolean hide, Component owner) {
        Path path = preferencesFile();
        try {
            Properties prefs = new Properties();
            if (Files.exists(path)) try (InputStream in = Files.newInputStream(path)) { prefs.load(in); }
            prefs.setProperty("close-to-tray", Boolean.toString(hide));
            Files.createDirectories(path.getParent());
            Path temp = Files.createTempFile(path.getParent(), "desktop-", ".tmp");
            try {
                try (OutputStream out = Files.newOutputStream(temp)) { prefs.store(out, "airplay desktop preferences"); }
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save desktop preferences", e);
        }
    }
    public static JComponent decorateSettings(JComponent view, Object model) {
        JPanel stack = (JPanel) field(model, "settingsStack");
        I18n i18n = (I18n) field(model, "i18n");
        JComboBox<String> choice = new JComboBox<>(); choice.setName("airplay.closeBehavior");
        choice.setPreferredSize(new Dimension(200, 34));
        boolean initial = hideOnClose();
        Runnable labels = () -> {
            int index = choice.getSelectedIndex();
            choice.removeAllItems();
            choice.addItem(i18n.tr("desktop.close.exit")); choice.addItem(i18n.tr("desktop.close.tray"));
            choice.setSelectedIndex(index < 0 ? (initial ? 1 : 0) : index);
            choice.getAccessibleContext().setAccessibleName(i18n.tr("desktop.close.title"));
        };
        labels.run();
        final boolean[] updating = {false};
        i18n.addLanguageChangeListener(() -> { updating[0] = true; try { labels.run(); } finally { updating[0] = false; } });
        choice.addActionListener(event -> {
            if (!updating[0] && choice.getSelectedIndex() >= 0) {
                try { saveCloseBehavior(choice.getSelectedIndex() == 1, choice); }
                catch (RuntimeException e) { JOptionPane.showMessageDialog(choice, e.getMessage(), "airplay", JOptionPane.ERROR_MESSAGE); }
            }
        });
        JComponent row = (JComponent) call(model, "settingRow", new Class<?>[]{String.class, String.class, JComponent.class},
            "desktop.close.title", "desktop.close.description", choice);
        Component toggleRow = (Component) field(model, "alwaysOnTopToggle");
        while (toggleRow.getParent() != stack && toggleRow.getParent() != null) toggleRow = toggleRow.getParent();
        int index = stack.getComponentZOrder(toggleRow);
        stack.add(row, index < 0 ? stack.getComponentCount() : index + 1);
        JCheckBox audioEnabled = new JCheckBox(); audioEnabled.setOpaque(false);
        audioEnabled.setName("airplay.airplay2.enabled");
        JLabel audioStatus = new JLabel(); audioStatus.setName("airplay.airplay2.status");
        JPanel audioControl = new JPanel(); audioControl.setOpaque(false);
        audioControl.setLayout(new BoxLayout(audioControl, BoxLayout.Y_AXIS));
        audioControl.add(audioEnabled); audioControl.add(audioStatus);
        audioEnabled.setSelected((Boolean) audioBackend("enabled", new Class<?>[0]));
        Runnable refresh = () -> {
            ThemeManager.ThemePalette palette = ((ThemeManager) field(model, "themeManager")).palette();
            audioEnabled.setForeground(palette.textPrimary());
            audioStatus.setForeground(palette.textSecondary());
            audioEnabled.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
            audioStatus.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 11));
            audioEnabled.setText(i18n.tr("airplay2.enabled"));
            String state = (String) audioBackend("state", new Class<?>[0]);
            audioStatus.setText(i18n.tr("airplay2.state." + state));
            audioStatus.setToolTipText((String) audioBackend("detail", new Class<?>[0]));
        };
        audioEnabled.addActionListener(event -> {
            try { audioBackend("setEnabled", new Class<?>[]{boolean.class}, audioEnabled.isSelected()); }
            catch (RuntimeException e) { JOptionPane.showMessageDialog(audioEnabled, e.getMessage(), "airplay", JOptionPane.ERROR_MESSAGE); }
            refresh.run();
        });
        refresh.run(); i18n.addLanguageChangeListener(refresh);
        ((ThemeManager) field(model, "themeManager")).addListener(refresh);
        javax.swing.Timer audioTimer = new javax.swing.Timer(1000, event -> refresh.run());
        audioControl.addHierarchyListener(event -> {
            if (audioControl.isShowing()) audioTimer.start(); else audioTimer.stop();
        });
        JComponent audioRow = (JComponent) call(model, "settingRow", new Class<?>[]{String.class, String.class, JComponent.class},
            "airplay2.title", "airplay2.description", audioControl);
        stack.add(audioRow, Math.min(stack.getComponentCount(), stack.getComponentZOrder(row) + 1));
        return view;
    }

    private static Object audioBackend(String name, Class<?>[] types, Object... arguments) {
        try {
            return Class.forName("wtf.nanoka.airplay.app.AirPlay2Audio").getMethod(name, types).invoke(null, arguments);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("AirPlay 2: " + name, e); }
    }

    public static void showMainFrame(JFrame frame, boolean visible, Object model) {
        install(model, true); frame.setVisible(visible);
    }
    public static void installDetached(Object model) {
        Container body = frame(model).getContentPane();
        if (body.getLayout() instanceof BorderLayout layout) {
            Component status = layout.getLayoutComponent(BorderLayout.SOUTH);
            if (status != null) body.remove(status);
        }
        install(model, false);
    }
    @SuppressWarnings("unchecked")
    private static void install(Object model, boolean main) {
        JFrame frame = frame(model);
        if (chrome(frame) != null) return;
        if (frame.isDisplayable()) throw new IllegalStateException("Chrome must be installed before creating native windows");
        frame.setUndecorated(true); frame.setAlwaysOnTop(false);
        I18n i18n = (I18n) field(model, "i18n");
        Supplier<ThemeManager.ThemePalette> palette = main
            ? ((ThemeManager) field(model, "themeManager"))::palette
            : (Supplier<ThemeManager.ThemePalette>) field(model, "palette");
        Container body = frame.getContentPane();
        frame.getRootPane().putClientProperty(BODY, body);
        Chrome chrome = new Chrome(frame, model, palette, i18n);
        frame.getRootPane().putClientProperty(CHROME, chrome);
        JPanel content = new JPanel(new BorderLayout());
        content.add(chrome, BorderLayout.NORTH); content.add(body, BorderLayout.CENTER);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(content, BorderLayout.CENTER);
        addResizeEdge(wrapper, frame, BorderLayout.WEST, Cursor.W_RESIZE_CURSOR, -1, 0);
        addResizeEdge(wrapper, frame, BorderLayout.EAST, Cursor.E_RESIZE_CURSOR, 1, 0);
        addResizeEdge(wrapper, frame, BorderLayout.SOUTH, Cursor.S_RESIZE_CURSOR, 0, 1);
        addResizeEdge(wrapper, frame, BorderLayout.NORTH, Cursor.N_RESIZE_CURSOR, 0, -1);
        frame.setContentPane(wrapper);
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "airplay.exitFull");
        frame.getRootPane().getActionMap().put("airplay.exitFull", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { if (chrome.fullscreen) chrome.setFullscreen(false); }
        });
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "airplay.toggleFull");
        frame.getRootPane().getActionMap().put("airplay.toggleFull", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { chrome.setFullscreen(!chrome.fullscreen); }
        });
        if (main) {
            ((JComponent) field(model, "fullscreenButton")).setVisible(false);
            JFrame detached = frame(field(model, "detachedVideoWindow"));
            detached.getRootPane().putClientProperty("airplay.main", frame);
            frame.addPropertyChangeListener("alwaysOnTop", e -> {
                ((AbstractButton) field(model, "alwaysOnTopToggle")).setSelected(frame.isAlwaysOnTop());
            });
        }
    }
    private static void addResizeEdge(JPanel wrapper, JFrame frame, String side, int cursor, int horizontal, int vertical) {
        JPanel grip = new JPanel(); grip.setPreferredSize(new Dimension(4, 4));
        grip.setCursor(Cursor.getPredefinedCursor(cursor));
        MouseAdapter resize = new MouseAdapter() {
            Point press; Rectangle bounds;
            @Override public void mousePressed(MouseEvent e) { press = e.getLocationOnScreen(); bounds = frame.getBounds(); }
            @Override public void mouseDragged(MouseEvent e) {
                if (chrome(frame).fullscreen || frame.getExtendedState() != JFrame.NORMAL) return;
                int dx = e.getXOnScreen() - press.x, dy = e.getYOnScreen() - press.y;
                Rectangle next = new Rectangle(bounds);
                if (horizontal < 0) { next.x += dx; next.width -= dx; }
                if (horizontal > 0) next.width += dx;
                if (vertical < 0) { next.y += dy; next.height -= dy; }
                if (vertical > 0) next.height += dy;
                if (next.width >= frame.getMinimumSize().width && next.height >= frame.getMinimumSize().height) frame.setBounds(next);
            }
        };
        grip.addMouseListener(resize); grip.addMouseMotionListener(resize); wrapper.add(grip, side);
    }
    public static void toggleFullscreen(Object model) {
        edt(() -> {
            JFrame target = frame(model);
            if (model instanceof VisionPlayerWindow && !target.isVisible()) {
                JFrame detached = frame(field(model, "detachedVideoWindow"));
                if (detached.isVisible()) target = detached;
            }
            Chrome c = chrome(target); c.setFullscreen(!c.fullscreen);
        });
    }
    public static void setFullscreen(Object model, boolean value) { edt(() -> chrome(frame(model)).setFullscreen(value)); }
    public static void prepareHide(JFrame frame) { Chrome c = chrome(frame); if (c != null && c.fullscreen) c.setFullscreen(false); }
    public static void lockCloseOperation(Object model) { frame(model).setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); }
    public static void afterDetach(Object model) {
        JFrame main = frame(model); prepareHide(main); main.setVisible(false);
    }
    public static void detachedShown(Object model) {
        Container body = body(frame(model));
        if (body.getLayout() instanceof BorderLayout layout) {
            Component status = layout.getLayoutComponent(BorderLayout.SOUTH);
            if (status != null) { body.remove(status); body.revalidate(); body.repaint(); }
        }
        JFrame main = (JFrame) frame(model).getRootPane().getClientProperty("airplay.main");
        if (main != null) { prepareHide(main); main.setVisible(false); }
    }
    public static void afterAttach(Object model) {
        JFrame main = frame(model);
        if (chrome(main) != null && !Boolean.TRUE.equals(main.getRootPane().getClientProperty("airplay.exiting"))) {
            main.setVisible(true); main.setExtendedState(JFrame.NORMAL); main.toFront();
        }
    }
    public static void detachedHidden(Object model) { prepareHide(frame(model)); }

    private static final class Chrome extends JPanel {
        final JFrame frame; final Object model; final Supplier<ThemeManager.ThemePalette> palette; final I18n i18n;
        final JLabel title = new JLabel(); final Control pin, full, minimize, maximize, close;
        boolean fullscreen; Rectangle normalBounds; int normalState; GraphicsDevice device;
        Chrome(JFrame frame, Object model, Supplier<ThemeManager.ThemePalette> palette, I18n i18n) {
            super(new BorderLayout()); this.frame = frame; this.model = model; this.palette = palette; this.i18n = i18n;
            setName("airplay.titlebar"); setPreferredSize(new Dimension(100, 36));
            title.setText(frame.getTitle()); title.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
            title.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12)); title.setMinimumSize(new Dimension(0, 24));
            add(title, BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); actions.setOpaque(false);
            pin = new Control("pin", () -> frame.setAlwaysOnTop(!frame.isAlwaysOnTop()));
            full = new Control("fullscreen", () -> setFullscreen(!fullscreen));
            minimize = new Control("minimize", () -> { if (fullscreen) setFullscreen(false); frame.setExtendedState(frame.getExtendedState() | JFrame.ICONIFIED); });
            maximize = new Control("maximize", this::maximize);
            close = new Control("close", () -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
            for (Control control : new Control[]{pin, full, minimize, maximize, close}) actions.add(control);
            add(actions, BorderLayout.EAST);
            frame.addPropertyChangeListener("title", e -> title.setText(frame.getTitle()));
            frame.addPropertyChangeListener("alwaysOnTop", e -> { pin.setSelected(frame.isAlwaysOnTop()); labels(); });
            frame.addWindowStateListener(e -> labels());
            if (model instanceof VisionPlayerWindow) {
                ((ThemeManager) field(model, "themeManager")).addListener(this::repaint);
            }
            i18n.addLanguageChangeListener(this::labels); labels();
            MouseAdapter drag = new MouseAdapter() {
                Point press;
                @Override public void mousePressed(MouseEvent e) { press = e.getPoint(); }
                @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) maximize(); }
                @Override public void mouseDragged(MouseEvent e) {
                    if (!fullscreen && frame.getExtendedState() == JFrame.NORMAL)
                        frame.setLocation(e.getXOnScreen() - press.x - 4, e.getYOnScreen() - press.y - 4);
                }
            };
            title.addMouseListener(drag); title.addMouseMotionListener(drag);
        }
        void labels() {
            boolean zh = i18n.language() == I18n.Language.CHINESE;
            full.label(zh ? (fullscreen ? "退出全屏" : "全屏") : (fullscreen ? "Exit full screen" : "Full screen"));
            pin.label(zh ? (frame.isAlwaysOnTop() ? "取消置顶" : "置顶") : (frame.isAlwaysOnTop() ? "Unpin" : "Pin"));
            boolean maximized = (frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
            minimize.label(zh ? "最小化" : "Minimize");
            maximize.label(zh ? (maximized ? "还原" : "最大化") : (maximized ? "Restore" : "Maximize"));
            close.label(zh ? "关闭" : "Close"); repaint();
        }
        void maximize() {
            if (fullscreen) setFullscreen(false);
            GraphicsConfiguration config = frame.getGraphicsConfiguration(); Rectangle bounds = new Rectangle(config.getBounds());
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
            bounds.x += insets.left; bounds.y += insets.top; bounds.width -= insets.left + insets.right; bounds.height -= insets.top + insets.bottom;
            frame.setMaximizedBounds(bounds);
            frame.setExtendedState((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH ? JFrame.NORMAL : JFrame.MAXIMIZED_BOTH);
        }
        void setFullscreen(boolean value) {
            if (fullscreen == value) return;
            if (value) {
                normalBounds = frame.getBounds(); normalState = frame.getExtendedState(); device = frame.getGraphicsConfiguration().getDevice();
                frame.setExtendedState(JFrame.NORMAL); fullscreen = true;
                try { device.setFullScreenWindow(frame); }
                catch (RuntimeException e) { frame.setBounds(device.getDefaultConfiguration().getBounds()); }
            } else {
                fullscreen = false;
                if (device != null && device.getFullScreenWindow() == frame) device.setFullScreenWindow(null);
                if (normalBounds != null) frame.setBounds(normalBounds);
                frame.setExtendedState(normalState);
            }
            full.setSelected(fullscreen); labels(); frame.validate();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try { clearBackground(g, getWidth(), getHeight(), palette.get()); }
            finally { g.dispose(); }
            title.setForeground(palette.get().textPrimary());
        }
        final class Control extends JToggleButton {
            final String kind;
            Control(String kind, Runnable action) {
                this.kind = kind; setName("airplay." + kind); setPreferredSize(new Dimension(42, 36));
                setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(true);
                addActionListener(e -> { action.run(); if (!kind.equals("pin") && !kind.equals("fullscreen")) setSelected(false); });
            }
            void label(String value) { setToolTipText(value); getAccessibleContext().setAccessibleName(value); }
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    var colors = palette.get();
                    clearBackground(g, getWidth(), getHeight(), colors);
                    if (getModel().isRollover() || isSelected() || hasFocus()) {
                        g.setColor(kind.equals("close") && getModel().isRollover() ? colors.danger() : (isSelected() ? colors.selected() : colors.controlHover()));
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                    g.setColor(isSelected() ? colors.selectedText() : colors.textPrimary());
                    g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    int x = getWidth() / 2, y = getHeight() / 2;
                    switch (kind) {
                        case "pin" -> {
                            boolean pinned = frame.isAlwaysOnTop();
                            if (!pinned) g.rotate(Math.PI / 4, x, y);
                            java.awt.geom.Path2D head = new java.awt.geom.Path2D.Double();
                            head.moveTo(x-5,y-7); head.lineTo(x+5,y-7); head.lineTo(x+3,y-5);
                            head.lineTo(x+3,y); head.lineTo(x+6,y+4); head.lineTo(x-6,y+4);
                            head.lineTo(x-3,y); head.lineTo(x-3,y-5); head.closePath();
                            if (pinned) g.fill(head); else g.draw(head);
                            g.drawLine(x,y+4,x,y+9);
                        }
                        case "fullscreen" -> {
                            for (int sx : new int[]{-1,1}) for (int sy : new int[]{-1,1}) {
                                int corner = fullscreen ? 2 : 7;
                                int end = fullscreen ? 7 : 2;
                                g.drawLine(x+sx*corner,y+sy*corner,x+sx*end,y+sy*corner);
                                g.drawLine(x+sx*corner,y+sy*corner,x+sx*corner,y+sy*end);
                            }
                        }
                        case "minimize" -> g.drawLine(x-6,y+3,x+6,y+3);
                        case "maximize" -> {
                            if ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH) {
                                g.drawLine(x-2,y-6,x+6,y-6); g.drawLine(x+6,y-6,x+6,y+2);
                                g.drawRect(x-6,y-2,8,8);
                            } else g.drawRect(x-5,y-5,10,10);
                        }
                        case "close" -> { g.drawLine(x-5,y-5,x+5,y+5); g.drawLine(x+5,y-5,x-5,y+5); }
                    }
                } finally { g.dispose(); }
            }
        }
    }
}
