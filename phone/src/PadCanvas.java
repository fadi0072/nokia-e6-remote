import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Full-screen touchpad + keyboard capture.
 *
 *  +-----------------------------------------------+
 *  | o Connected to 192.168.1.5        [Text][Setup]|
 *  +--------------------------------------+--------+
 *  |                                      |   ^    |
 *  |            touchpad                  | scroll |
 *  |                                      |   v    |
 *  +--------------------------------------+--------+
 *  | L | R | Drag | Cmd | Ctrl | Alt | Shift | Esc | Tab |
 *  +-----------------------------------------------+
 *
 * Pad: drag = move, tap = left click, long press = right click.
 * Modifier buttons cycle: off -> next key only (blue) -> locked (orange) -> off.
 */
public class PadCanvas extends Canvas {
    static final int MOD_CMD = 1, MOD_CTRL = 2, MOD_ALT = 4, MOD_SHIFT = 8;

    private static final String[] BTN = { "L", "R", "Drag", "Cmd", "Ctrl", "Alt", "Shift", "Esc", "Tab" };
    private static final int[] BTN_MOD = { 0, 0, 0, MOD_CMD, MOD_CTRL, MOD_ALT, MOD_SHIFT, 0, 0 };
    private static final String[] TOP = { "Text", "Setup" };

    private static final int R_NONE = 0, R_PAD = 1, R_SCROLL = 2, R_BTN = 3, R_TOP = 4;
    private static final int TAP_SLOP = 18;       // px of finger travel still counted as a tap
    private static final long LONG_PRESS_MS = 700; // hold this long without moving = right click

    private static final int C_BG = 0x111114, C_BAR = 0x232328, C_PAD = 0x1b1b20, C_EDGE = 0x3a3a42,
            C_TEXT = 0xe8e8ee, C_DIM = 0x8a8a96, C_BTN = 0x2c2c33, C_PRESS = 0x4a4a55,
            C_ONCE = 0x0a84ff, C_LOCK = 0xff9f0a, C_DRAG = 0x30d158, C_RED = 0xff453a;

    private final E6Remote app;
    private final Link link;

    private int modsOnce, modsLocked;
    private boolean dragging;

    private int region = R_NONE, pressed = -1;
    private int lastX, lastY, travel;
    private long downTime;
    private boolean padTouched;

    private final Font fSmall = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fBold = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_MEDIUM);

    public PadCanvas(E6Remote app, Link link) {
        this.app = app;
        this.link = link;
        setFullScreenMode(true);
    }

    // ---- layout -------------------------------------------------------

    private int topH() { return Math.max(fBold.getHeight() + 14, getHeight() / 10); }
    private int botH() { return Math.max(fBold.getHeight() + 22, getHeight() / 7); }
    private int scrollW() { return Math.max(56, getWidth() / 9); }
    private int topBtnW() { return Math.max(fBold.stringWidth("Setup") + 24, getWidth() / 7); }

    private int bottomHit(int x) {
        int i = x * BTN.length / getWidth();
        return i < 0 ? 0 : (i >= BTN.length ? BTN.length - 1 : i);
    }

    private int topHit(int x) {
        int bw = topBtnW();
        int w = getWidth();
        if (x >= w - bw) return 1;
        if (x >= w - 2 * bw) return 0;
        return -1;
    }

    protected void sizeChanged(int w, int h) {
        repaint();
    }

    // ---- drawing ------------------------------------------------------

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        int th = topH(), bh = botH(), sw = scrollW();

        g.setColor(C_BG);
        g.fillRect(0, 0, w, h);

        // top bar
        g.setColor(C_BAR);
        g.fillRect(0, 0, w, th);
        int st = link.getState();
        int dot = st == Link.CONNECTED ? C_DRAG : (st == Link.CONNECTING ? C_LOCK : C_RED);
        int d = fSmall.getHeight() / 2 + 2;
        g.setColor(dot);
        g.fillArc(10, (th - d) / 2, d, d, 0, 360);
        g.setFont(fSmall);
        g.setColor(C_TEXT);
        int bw = topBtnW();
        drawClipped(g, link.getStatus(), 18 + d, th / 2 - fSmall.getHeight() / 2, w - 2 * bw - 24 - d);
        for (int i = 0; i < TOP.length; i++) {
            int x = w - (TOP.length - i) * bw;
            drawButton(g, TOP[i], x + 3, 4, bw - 6, th - 8,
                    region == R_TOP && pressed == i ? C_PRESS : C_BTN, C_TEXT);
        }

        // touchpad
        int padY = th, padH = h - th - bh;
        int padW = w - sw;
        g.setColor(padTouched && region == R_PAD ? 0x202028 : C_PAD);
        g.fillRect(0, padY, padW, padH);
        g.setColor(C_EDGE);
        g.drawRect(4, padY + 4, padW - 9, padH - 9);
        g.setFont(fSmall);
        g.setColor(C_DIM);
        int cy = padY + padH / 2;
        int lh = fSmall.getHeight();
        if (dragging) {
            g.setColor(C_DRAG);
            g.setFont(fBold);
            g.drawString("DRAGGING", padW / 2, cy - lh, Graphics.TOP | Graphics.HCENTER);
            g.setFont(fSmall);
            g.drawString("tap the pad or Drag to drop", padW / 2, cy + 4, Graphics.TOP | Graphics.HCENTER);
        } else {
            g.drawString("Slide to move  -  Tap to click", padW / 2, cy - lh - 2, Graphics.TOP | Graphics.HCENTER);
            g.drawString("Hold to right-click  -  Type on keyboard", padW / 2, cy + 2, Graphics.TOP | Graphics.HCENTER);
        }

        // scroll strip
        g.setColor(region == R_SCROLL ? C_PRESS : 0x26262d);
        g.fillRect(padW, padY, sw, padH);
        g.setColor(C_EDGE);
        g.drawLine(padW, padY, padW, padY + padH);
        g.setColor(C_DIM);
        int ax = padW + sw / 2, a = sw / 5;
        g.fillTriangle(ax, padY + 14, ax - a, padY + 14 + a, ax + a, padY + 14 + a);
        g.fillTriangle(ax, padY + padH - 14, ax - a, padY + padH - 14 - a, ax + a, padY + padH - 14 - a);
        for (int y = padY + padH / 2 - 20; y <= padY + padH / 2 + 20; y += 10) {
            g.drawLine(ax - a, y, ax + a, y);
        }

        // bottom buttons
        int by = h - bh;
        g.setColor(C_BAR);
        g.fillRect(0, by, w, bh);
        g.setFont(fBold);
        for (int i = 0; i < BTN.length; i++) {
            int x0 = i * w / BTN.length, x1 = (i + 1) * w / BTN.length;
            int bg = C_BTN, fg = C_TEXT;
            int m = BTN_MOD[i];
            if (m != 0 && (modsLocked & m) != 0) { bg = C_LOCK; fg = 0x000000; }
            else if (m != 0 && (modsOnce & m) != 0) { bg = C_ONCE; fg = 0xffffff; }
            else if (i == 2 && dragging) { bg = C_DRAG; fg = 0x000000; }
            if (region == R_BTN && pressed == i) bg = C_PRESS;
            drawButton(g, BTN[i], x0 + 3, by + 5, x1 - x0 - 6, bh - 10, bg, fg);
        }
    }

    private void drawButton(Graphics g, String label, int x, int y, int w, int h, int bg, int fg) {
        g.setColor(bg);
        g.fillRoundRect(x, y, w, h, 12, 12);
        g.setColor(fg);
        Font f = g.getFont();
        g.drawString(label, x + w / 2, y + (h - f.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
    }

    private void drawClipped(Graphics g, String s, int x, int y, int maxW) {
        Font f = g.getFont();
        if (f.stringWidth(s) > maxW) {
            while (s.length() > 1 && f.stringWidth(s + "..") > maxW) s = s.substring(0, s.length() - 1);
            s = s + "..";
        }
        g.drawString(s, x, y, Graphics.TOP | Graphics.LEFT);
    }

    // ---- touch --------------------------------------------------------

    protected void pointerPressed(int x, int y) {
        lastX = x;
        lastY = y;
        travel = 0;
        downTime = System.currentTimeMillis();
        int h = getHeight();
        if (y < topH()) {
            region = R_TOP;
            pressed = topHit(x);
        } else if (y >= h - botH()) {
            region = R_BTN;
            pressed = bottomHit(x);
        } else if (x >= getWidth() - scrollW()) {
            region = R_SCROLL;
        } else {
            region = R_PAD;
            padTouched = true;
        }
        repaint();
    }

    protected void pointerDragged(int x, int y) {
        int dx = x - lastX, dy = y - lastY;
        lastX = x;
        lastY = y;
        travel += Math.abs(dx) + Math.abs(dy);
        if (region == R_PAD) {
            link.move(dx, dy);
        } else if (region == R_SCROLL) {
            link.scroll(dy);
        }
    }

    protected void pointerReleased(int x, int y) {
        long held = System.currentTimeMillis() - downTime;
        int r = region, p = pressed;
        region = R_NONE;
        pressed = -1;
        padTouched = false;

        if (r == R_PAD && travel < TAP_SLOP) {
            if (dragging) {
                setDragging(false);
            } else {
                click(held >= LONG_PRESS_MS ? "R" : "L");
            }
        } else if (r == R_BTN && p >= 0 && y >= getHeight() - botH() && bottomHit(x) == p) {
            onButton(p);
        } else if (r == R_TOP && p >= 0 && y < topH() && topHit(x) == p) {
            if (p == 0) app.showTextInput();
            else app.showSettings();
        }
        repaint();
    }

    private void onButton(int i) {
        int m = BTN_MOD[i];
        if (m != 0) {
            // off -> once -> locked -> off
            if ((modsLocked & m) != 0) {
                modsLocked &= ~m;
            } else if ((modsOnce & m) != 0) {
                modsOnce &= ~m;
                modsLocked |= m;
            } else {
                modsOnce |= m;
            }
            return;
        }
        switch (i) {
            case 0: click("L"); break;
            case 1: click("R"); break;
            case 2: setDragging(!dragging); break;
            case 7: key("esc"); break;
            case 8: key("tab"); break;
        }
    }

    private void setDragging(boolean on) {
        dragging = on;
        link.send(on ? "D L" : "U L");
    }

    private void click(String button) {
        link.send("C " + button + " " + takeMods());
    }

    private void key(String name) {
        link.send("K " + name + " " + takeMods());
    }

    /** Current modifiers; one-shot modifiers are cleared after use. */
    private int takeMods() {
        int m = modsOnce | modsLocked;
        if (modsOnce != 0) {
            modsOnce = 0;
            repaint();
        }
        return m;
    }

    // ---- hardware keys ------------------------------------------------

    protected void keyPressed(int k) { handleKey(k); }
    protected void keyRepeated(int k) { handleKey(k); }

    private void handleKey(int k) {
        String name = null;
        switch (k) {
            case -1: name = "up"; break;
            case -2: name = "down"; break;
            case -3: name = "left"; break;
            case -4: name = "right"; break;
            case -5: name = "enter"; break;     // navi-key centre / Enter
            case -8: name = "backspace"; break; // Clear key
            case 8: name = "backspace"; break;
            case 9: name = "tab"; break;
            case 10: case 13: name = "enter"; break;
            case 27: name = "esc"; break;
            case 127: name = "delete"; break;
            default: break;
        }
        if (name == null && k < 0) {
            int ga = 0;
            try { ga = getGameAction(k); } catch (Exception e) { }
            switch (ga) {
                case UP: name = "up"; break;
                case DOWN: name = "down"; break;
                case LEFT: name = "left"; break;
                case RIGHT: name = "right"; break;
                case FIRE: name = "enter"; break;
                default: return; // soft keys, call key etc.: ignore
            }
        }
        if (name != null) {
            key(name);
        } else if (k > 0) {
            link.send("T " + k + " " + takeMods());
        }
    }

    /** Sends a whole string typed in the phone's text editor. */
    public void sendText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') link.send("K enter 0");
            else link.send("T " + (int) c + " 0");
        }
    }
}
