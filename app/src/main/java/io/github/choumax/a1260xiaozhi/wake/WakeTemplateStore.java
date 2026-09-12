package io.github.choumax.a1260xiaozhi.wake;

import android.content.Context;
import android.util.AtomicFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Three original PCM recordings in app-private no-backup storage. No exported audio files. */
public final class WakeTemplateStore {
    private static final Object LOCK = new Object();
    private static final int MAGIC = 0x57414B45, VERSION = 1;
    private final AtomicFile file;
    public WakeTemplateStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "wake-templates-v1.bin"));
    }
    public boolean exists() {
        synchronized (LOCK) {
            try (java.io.FileInputStream stream = file.openRead()) { return stream.getChannel().size() > 0; }
            catch (IOException e) { return false; }
        }
    }
    public void delete() { synchronized (LOCK) { file.delete(); } }

    public void save(short[][] recordings) throws IOException {
        if (recordings == null || recordings.length != 3) throw new IOException("Three recordings required");
        WakeFeatures extractor = new WakeFeatures();
        for (short[] recording : recordings) {
            try { extractor.extract(recording); } catch (IllegalArgumentException e) { throw new IOException("Invalid recording", e); }
        }
        synchronized (LOCK) {
            FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                DataOutputStream data = new DataOutputStream(new BufferedOutputStream(stream));
                data.writeInt(MAGIC); data.writeInt(VERSION); data.writeInt(WakeFeatures.SAMPLE_RATE); data.writeInt(3);
                for (short[] recording : recordings) {
                    data.writeInt(recording.length);
                    for (short value : recording) data.writeShort(value);
                }
                data.flush(); file.finishWrite(stream);
            } catch (IOException | RuntimeException e) {
                if (stream != null) file.failWrite(stream);
                throw new IOException("Cannot save wake recordings", e);
            }
        }
    }

    public float[][][] load() throws IOException {
        synchronized (LOCK) {
            try (DataInputStream data = new DataInputStream(new BufferedInputStream(file.openRead()))) {
                if (data.readInt() != MAGIC || data.readInt() != VERSION || data.readInt() != WakeFeatures.SAMPLE_RATE || data.readInt() != 3)
                    throw new IOException("Unsupported wake recordings");
                float[][][] templates = new float[3][][];
                WakeFeatures extractor = new WakeFeatures();
                for (int i = 0; i < 3; i++) {
                    int count = data.readInt();
                    if (count < WakeFeatures.MIN_SAMPLES || count > WakeFeatures.MAX_SAMPLES) throw new IOException("Invalid wake recording length");
                    short[] pcm = new short[count];
                    for (int j = 0; j < count; j++) pcm[j] = data.readShort();
                    try { templates[i] = extractor.extract(pcm); } finally { java.util.Arrays.fill(pcm, (short) 0); }
                }
                if (data.read() != -1) throw new IOException("Unexpected wake recording data");
                return templates;
            } catch (IllegalArgumentException e) { throw new IOException("Invalid wake recording audio", e); }
        }
    }
}
