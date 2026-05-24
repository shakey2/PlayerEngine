package com.player2.playerengine.modintelligence.ingest;

import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class LoadedModMetadataProvider {
    private LoadedModMetadataProvider() {}

    public static List<LoadedModMetadata> loadedMods() {
        List<LoadedModMetadata> out = new ArrayList<>();
        for (Mod mod : Platform.getMods()) {
            String modId = mod.getModId();
            if ("minecraft".equals(modId) || "java".equals(modId)) {
                continue;
            }
            String sourceHash = hashMod(mod);
            List<String> namespaces = List.of(modId);
            out.add(new LoadedModMetadata(modId, mod.getName(), mod.getVersion(), sourceHash, namespaces));
        }
        return out;
    }

    private static String hashMod(Mod mod) {
        try {
            var paths = mod.getFilePaths();
            if (!paths.isEmpty()) {
                var path = paths.get(0);
                if (Files.isRegularFile(path)) {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    try (var in = Files.newInputStream(path)) {
                        byte[] buf = new byte[8192];
                        int read;
                        while ((read = in.read(buf)) > 0) {
                            md.update(buf, 0, read);
                        }
                    }
                    return "sha256:jar:" + bytesToHex(md.digest());
                }
            }
        } catch (Exception ignored) {
        }
        String meta = mod.getModId() + "|" + mod.getVersion() + "|" + mod.getName();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(meta.getBytes());
            return "sha256:meta:" + bytesToHex(hash);
        } catch (Exception e) {
            return "sha256:meta:unknown";
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
