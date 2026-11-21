package com.herabarman.meshnet.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One file per key in a directory. Plain java.io, so it works on Android (pass context.getFilesDir()/"mesh").
 * Writes go to a temp file first and are renamed into place, so a crash never leaves a half-written value.
 */
public final class FileStorage implements StorageAdapter {
    private static final String TMP = ".tmp";
    private final File dir;
    private final boolean sync;

    public FileStorage(File dir) {
        this(dir, true);
    }

    /** @param sync fsync every write (safer on power loss, slower) */
    public FileStorage(File dir, boolean sync) {
        this.dir = dir;
        this.sync = sync;
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("cannot create " + dir);
        }
    }

    private static String encode(String key) {
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') sb.append(c);
            else sb.append('%').append(String.format("%04x", (int) c));
        }
        return sb.toString();
    }

    private static String decode(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '%' && i + 5 <= name.length()) {
                sb.append((char) Integer.parseInt(name.substring(i + 1, i + 5), 16));
                i += 4;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public void put(String key, byte[] value) {
        File target = new File(dir, encode(key));
        File tmp = new File(dir, encode(key) + TMP);
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(value);
                out.flush();
                if (sync) out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(target)) {
                target.delete();
                if (!tmp.renameTo(target)) throw new IOException("rename failed for " + key);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] get(String key) {
        File f = new File(dir, encode(key));
        if (!f.isFile()) return null;
        try {
            InputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void remove(String key) {
        new File(dir, encode(key)).delete();
    }

    @Override
    public List<String> keys(String prefix) {
        List<String> out = new ArrayList<>();
        String[] names = dir.list();
        if (names == null) return out;
        for (String name : names) {
            if (name.endsWith(TMP)) continue;
            String key = decode(name);
            if (key.startsWith(prefix)) out.add(key);
        }
        return out;
    }
}
