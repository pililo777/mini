package com.pililo777.minissh;

import android.util.Base64;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.UserInfo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class PinnedHostKeyRepository implements HostKeyRepository {
    private final String expectedFingerprint;
    private volatile String lastReceivedFingerprint;

    public PinnedHostKeyRepository(String expectedFingerprint) {
        this.expectedFingerprint = normalize(expectedFingerprint);
        if (this.expectedFingerprint == null) {
            throw new IllegalArgumentException("Invalid SHA-256 fingerprint");
        }
    }

    public String getLastReceivedFingerprint() {
        return lastReceivedFingerprint;
    }

    public static String normalize(String value) {
        if (value == null) return null;
        String compact = value.trim().replace(" ", "");
        if (compact.isEmpty()) return null;
        String body = compact.regionMatches(true, 0, "SHA256:", 0, 7)
                ? compact.substring(7)
                : compact;
        if (body.length() < 20) return null;
        return "SHA256:" + body;
    }

    public static boolean same(String first, String second) {
        if (first == null || second == null) return false;
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public int check(String host, byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            String body = Base64.encodeToString(digest, Base64.NO_WRAP | Base64.NO_PADDING);
            lastReceivedFingerprint = "SHA256:" + body;
            return same(expectedFingerprint, lastReceivedFingerprint) ? OK : CHANGED;
        } catch (Exception e) {
            return NOT_INCLUDED;
        }
    }

    @Override public void add(HostKey hostkey, UserInfo ui) { }
    @Override public void remove(String host, String type) { }
    @Override public void remove(String host, String type, byte[] key) { }
    @Override public String getKnownHostsRepositoryID() { return "Pinned SHA-256 fingerprint"; }
    @Override public HostKey[] getHostKey() { return new HostKey[0]; }
    @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
}
