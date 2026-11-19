import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.SocketConnection;
import javax.microedition.io.StreamConnection;

/**
 * Background network thread. Keeps a TCP connection to the Mac server open,
 * reconnects when it drops, and sends queued commands. Mouse/scroll deltas
 * that arrive faster than the network can carry them are merged together.
 *
 * Protocol (one ASCII line per command):
 *   H pin speed     hello (server answers OK or NO)
 *   M dx dy         move pointer
 *   S dy            scroll
 *   C L|R mods      click
 *   D L / U L       button down / up (drag)
 *   T code mods     type unicode character
 *   K name mods     special key (enter, backspace, up, ...)
 *   P               keep-alive
 */
public class Link implements Runnable {
    public static final int IDLE = 0;
    public static final int CONNECTING = 1;
    public static final int CONNECTED = 2;
    public static final int BAD_PIN = 3;
    public static final int ERROR = 4;

    private final Vector queue = new Vector();
    private volatile boolean running = true;

    private String host = "";
    private int port = 5555;
    private String pin = "";
    private int speed = 5;
    private int generation = 0;

    private volatile int state = IDLE;
    private volatile String status = "Not configured";
    private StreamConnection current;
    private PadCanvas ui;

    public void setUi(PadCanvas ui) {
        this.ui = ui;
    }

    public int getState() { return state; }
    public String getStatus() { return status; }

    /** Apply new settings; forces a reconnect. */
    public void configure(String host, int port, String pin, int speed) {
        synchronized (queue) {
            this.host = host == null ? "" : host.trim();
            this.port = port;
            this.pin = pin == null ? "" : pin.trim();
            this.speed = speed;
            generation++;
            queue.removeAllElements();
            queue.notifyAll();
        }
        closeCurrent();
    }

    public void stop() {
        running = false;
        synchronized (queue) { queue.notifyAll(); }
        closeCurrent();
    }

    public void move(int dx, int dy) {
        if (dx != 0 || dy != 0) enqueueDelta('M', dx, dy);
    }

    public void scroll(int dy) {
        if (dy != 0) enqueueDelta('S', 0, dy);
    }

    public void send(String cmd) {
        synchronized (queue) {
            if (state != CONNECTED) return;
            queue.addElement(cmd);
            queue.notifyAll();
        }
    }

    private void enqueueDelta(int type, int dx, int dy) {
        synchronized (queue) {
            if (state != CONNECTED) return;
            if (!queue.isEmpty()) {
                Object last = queue.lastElement();
                if (last instanceof int[]) {
                    int[] d = (int[]) last;
                    if (d[0] == type) {
                        d[1] += dx;
                        d[2] += dy;
                        return;
                    }
                }
            }
            queue.addElement(new int[] { type, dx, dy });
            queue.notifyAll();
        }
    }

    private void setState(int s, String msg) {
        state = s;
        status = msg;
        if (ui != null) ui.repaint();
    }

    public void run() {
        while (running) {
            String h, pn;
            int p, sp, gen;
            synchronized (queue) {
                while (running && host.length() == 0) {
                    setState(IDLE, "Not configured - tap Setup");
                    waitOn(0);
                }
                if (!running) break;
                h = host; p = port; pn = pin; sp = speed; gen = generation;
            }

            setState(CONNECTING, "Connecting to " + h + "...");
            StreamConnection sc = null;
            InputStream in = null;
            OutputStream out = null;
            boolean retryLater = true;
            try {
                sc = (StreamConnection) Connector.open("socket://" + h + ":" + p, Connector.READ_WRITE, true);
                synchronized (this) { current = sc; }
                if (sc instanceof SocketConnection) {
                    try {
                        SocketConnection s = (SocketConnection) sc;
                        s.setSocketOption(SocketConnection.DELAY, 0);
                        s.setSocketOption(SocketConnection.KEEPALIVE, 1);
                    } catch (Exception ignored) { }
                }
                out = sc.openOutputStream();
                in = sc.openInputStream();
                write(out, "H " + pn + " " + sp + "\n");
                String resp = readLine(in);
                if (!"OK".equals(resp)) {
                    setState(BAD_PIN, "Wrong PIN - check Setup");
                    retryLater = false;
                } else {
                    synchronized (queue) {
                        queue.removeAllElements();
                        if (gen == generation) setState(CONNECTED, "Connected to " + h);
                    }
                    pump(out, gen);
                }
            } catch (Throwable e) {
                if (running) {
                    String m = e.getMessage();
                    setState(ERROR, "Can't reach " + h + (m != null ? " (" + m + ")" : ""));
                }
            } finally {
                synchronized (this) { current = null; }
                try { if (in != null) in.close(); } catch (Exception e) { }
                try { if (out != null) out.close(); } catch (Exception e) { }
                try { if (sc != null) sc.close(); } catch (Exception e) { }
            }

            if (!running) break;
            synchronized (queue) {
                if (gen != generation) continue; // settings changed: reconnect immediately
                if (state == CONNECTED) setState(ERROR, "Disconnected - retrying");
                if (retryLater) {
                    waitOn(2000);
                } else {
                    while (running && gen == generation) waitOn(0); // wait for new PIN
                }
            }
        }
        setState(IDLE, "Stopped");
    }

    /** Sends queued commands until the connection breaks or settings change. */
    private void pump(OutputStream out, int gen) throws IOException {
        StringBuffer sb = new StringBuffer();
        while (running) {
            sb.setLength(0);
            synchronized (queue) {
                if (gen != generation) return;
                if (queue.isEmpty()) {
                    waitOn(3000);
                    if (!running || gen != generation) return;
                }
                if (queue.isEmpty()) {
                    sb.append("P\n"); // keep-alive, also detects a dead link
                }
                while (!queue.isEmpty()) {
                    Object o = queue.elementAt(0);
                    queue.removeElementAt(0);
                    if (o instanceof int[]) {
                        int[] d = (int[]) o;
                        if (d[0] == 'M') {
                            sb.append("M ").append(d[1]).append(' ').append(d[2]).append('\n');
                        } else {
                            sb.append("S ").append(d[2]).append('\n');
                        }
                    } else {
                        sb.append((String) o).append('\n');
                    }
                }
            }
            write(out, sb.toString());
        }
    }

    private void waitOn(long ms) {
        try { queue.wait(ms); } catch (InterruptedException e) { }
    }

    private void closeCurrent() {
        StreamConnection c;
        synchronized (this) { c = current; }
        if (c != null) {
            try { c.close(); } catch (Exception e) { }
        }
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes());
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuffer sb = new StringBuffer();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 64) break;
        }
        if (c == -1 && sb.length() == 0) throw new IOException("closed");
        return sb.toString();
    }
}
