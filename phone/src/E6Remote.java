import java.util.Timer;
import java.util.TimerTask;

import javax.microedition.io.Connector;
import javax.microedition.io.Datagram;
import javax.microedition.io.DatagramConnection;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;

/** Nokia E6 -> Mac remote mouse & keyboard. */
public class E6Remote extends MIDlet implements CommandListener {
    static final int DISCOVERY_PORT = 5556;

    private Display display;
    private Config config;
    private Link link;
    private PadCanvas pad;

    private Form settings;
    private TextField fHost, fPort, fPin;
    private Gauge fSpeed;
    private StringItem fInfo;
    private TextBox textBox;

    private final Command cSave = new Command("Save", Command.OK, 1);
    private final Command cFind = new Command("Find Mac", Command.SCREEN, 2);
    private final Command cBack = new Command("Back", Command.BACK, 3);
    private final Command cExit = new Command("Exit", Command.EXIT, 4);
    private final Command cSend = new Command("Send", Command.OK, 1);
    private final Command cSendEnter = new Command("Send + Enter", Command.SCREEN, 2);
    private final Command cCancel = new Command("Cancel", Command.BACK, 3);

    private volatile boolean searching;

    protected void startApp() {
        if (display != null) {
            return; // resumed: keep current screen
        }
        display = Display.getDisplay(this);
        config = Config.load();
        // Optional defaults baked into the JAD (E6Remote-Host / E6Remote-Pin).
        if (config.host.length() == 0 && getAppProperty("E6Remote-Host") != null) {
            config.host = getAppProperty("E6Remote-Host").trim();
            if (getAppProperty("E6Remote-Pin") != null) config.pin = getAppProperty("E6Remote-Pin").trim();
        }
        link = new Link();
        pad = new PadCanvas(this, link);
        link.setUi(pad);
        new Thread(link).start();
        link.configure(config.host, config.port, config.pin, config.speed);
        if (config.host.length() == 0) {
            showSettings();
        } else {
            display.setCurrent(pad);
        }
    }

    protected void pauseApp() { }

    protected void destroyApp(boolean unconditional) {
        if (link != null) link.stop();
    }

    // ---- settings -----------------------------------------------------

    public void showSettings() {
        if (settings == null) {
            settings = new Form("E6 Remote setup");
            fHost = new TextField("Mac IP address", config.host, 40, TextField.ANY);
            fPort = new TextField("Port", String.valueOf(config.port), 5, TextField.NUMERIC);
            fPin = new TextField("PIN (shown by the Mac server)", config.pin, 8, TextField.NUMERIC);
            fSpeed = new Gauge("Pointer speed", true, 10, config.speed);
            fInfo = new StringItem(null, "Start the server on your Mac, then use 'Find Mac' or type its IP.");
            settings.append(fHost);
            settings.append(fPort);
            settings.append(fPin);
            settings.append(fSpeed);
            settings.append(fInfo);
            settings.addCommand(cSave);
            settings.addCommand(cFind);
            settings.addCommand(cBack);
            settings.addCommand(cExit);
            settings.setCommandListener(this);
        } else {
            fHost.setString(config.host);
            fPort.setString(String.valueOf(config.port));
            fPin.setString(config.pin);
            fSpeed.setValue(config.speed);
        }
        display.setCurrent(settings);
    }

    private void saveSettings() {
        config.host = fHost.getString().trim();
        try {
            config.port = Integer.parseInt(fPort.getString().trim());
        } catch (Exception e) {
            config.port = 5555;
        }
        config.pin = fPin.getString().trim();
        config.speed = Math.max(1, fSpeed.getValue());
        config.save();
        link.configure(config.host, config.port, config.pin, config.speed);
        display.setCurrent(pad);
    }

    /** Listens for the Mac server's UDP broadcast and fills in its address. */
    private void findMac() {
        if (searching) return;
        searching = true;
        fInfo.setText("Searching for the Mac (make sure both are on the same Wi-Fi)...");
        new Thread() {
            public void run() {
                DatagramConnection dc = null;
                Timer timer = new Timer();
                String found = null;
                try {
                    dc = (DatagramConnection) Connector.open("datagram://:" + DISCOVERY_PORT);
                    final DatagramConnection fdc = dc;
                    timer.schedule(new TimerTask() {
                        public void run() {
                            try { fdc.close(); } catch (Exception e) { }
                        }
                    }, 8000);
                    Datagram dg = dc.newDatagram(256);
                    for (int i = 0; i < 20 && found == null; i++) {
                        dg.reset();
                        dg.setLength(256);
                        dc.receive(dg);
                        String msg = new String(dg.getData(), dg.getOffset(), dg.getLength()).trim();
                        if (msg.startsWith("E6REMOTE ")) {
                            String addr = dg.getAddress(); // datagram://1.2.3.4:port
                            int s = addr.indexOf("//");
                            int e = addr.lastIndexOf(':');
                            if (s >= 0 && e > s) {
                                found = addr.substring(s + 2, e);
                                fHost.setString(found);
                                fPort.setString(msg.substring(9).trim());
                            }
                        }
                    }
                } catch (Throwable t) {
                    // timeout closes the socket -> lands here
                } finally {
                    timer.cancel();
                    try { if (dc != null) dc.close(); } catch (Exception e) { }
                    searching = false;
                }
                fInfo.setText(found != null
                        ? "Found Mac at " + found + ". Enter the PIN and choose Save."
                        : "No Mac found. Is the server running? You can type the IP manually.");
            }
        }.start();
    }

    // ---- text entry ---------------------------------------------------

    public void showTextInput() {
        if (textBox == null) {
            textBox = new TextBox("Type text to send", "", 1000, TextField.ANY);
            textBox.addCommand(cSend);
            textBox.addCommand(cSendEnter);
            textBox.addCommand(cCancel);
            textBox.setCommandListener(this);
        }
        textBox.setString("");
        display.setCurrent(textBox);
    }

    // ---- commands -----------------------------------------------------

    public void commandAction(Command c, Displayable d) {
        if (c == cSave) {
            saveSettings();
        } else if (c == cFind) {
            findMac();
        } else if (c == cBack) {
            display.setCurrent(pad);
        } else if (c == cExit) {
            destroyApp(true);
            notifyDestroyed();
        } else if (c == cSend || c == cSendEnter) {
            pad.sendText(textBox.getString());
            if (c == cSendEnter) pad.sendText("\n");
            display.setCurrent(pad);
        } else if (c == cCancel) {
            display.setCurrent(pad);
        }
    }
}
