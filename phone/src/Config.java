import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import javax.microedition.rms.RecordStore;

/** Persistent settings stored in the phone's RecordStore. */
public class Config {
    private static final String STORE = "e6remote";

    public String host = "";
    public int port = 5555;
    public String pin = "";
    public int speed = 5; // 1..10

    public static Config load() {
        Config c = new Config();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() > 0) {
                byte[] data = rs.getRecord(1);
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
                c.host = in.readUTF();
                c.port = in.readInt();
                c.pin = in.readUTF();
                c.speed = in.readInt();
            }
        } catch (Exception e) {
            // first run or corrupt store: keep defaults
        } finally {
            closeQuietly(rs);
        }
        if (c.speed < 1 || c.speed > 10) c.speed = 5;
        if (c.port <= 0 || c.port > 65535) c.port = 5555;
        return c;
    }

    public void save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeUTF(host);
            out.writeInt(port);
            out.writeUTF(pin);
            out.writeInt(speed);
            out.flush();
            byte[] data = bos.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (rs.getNumRecords() > 0) {
                rs.setRecord(1, data, 0, data.length);
            } else {
                rs.addRecord(data, 0, data.length);
            }
        } catch (Exception e) {
            // nothing sensible to do on a phone; settings just won't persist
        } finally {
            closeQuietly(rs);
        }
    }

    private static void closeQuietly(RecordStore rs) {
        if (rs != null) {
            try { rs.closeRecordStore(); } catch (Exception e) { }
        }
    }
}
