package com.agenthub.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class DocumentIdentity {

    private DocumentIdentity() {
    }

    public static String computeDocId(String sourceIdentity) {
        String normalized = computeSourceKey(sourceIdentity);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    public static String computeSourceKey(String sourceIdentity) {
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            return "unknown";
        }
        return sourceIdentity.trim()
                .replace('\\', '/')
                .replaceAll("/+", "/")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}a-z0-9._/-]+", "_");
    }

    public static String resolveDisplaySource(String displaySource, String fallback) {
        if (displaySource != null && !displaySource.isBlank()) {
            return displaySource.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return "unknown";
    }

    public static String extractTitle(String displaySource) {
        String resolved = resolveDisplaySource(displaySource, "unknown");
        String normalized = resolved.replace('\\', '/');
        String fileName = normalized.contains("/") ? normalized.substring(normalized.lastIndexOf('/') + 1) : normalized;
        int extensionIndex = fileName.lastIndexOf('.');
        return extensionIndex > 0 ? fileName.substring(0, extensionIndex) : fileName;
    }
}
