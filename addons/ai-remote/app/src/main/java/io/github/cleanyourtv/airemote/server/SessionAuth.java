package io.github.cleanyourtv.airemote.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Manages pairing tokens and session validation using SecureRandom and constant-time comparison.
 */
public final class SessionAuth {

    public static final String TOKEN_REGEX = "^[A-HJ-NP-Z2-9]{6}$";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // Removed ambiguous 0, O, 1, I
    private static final int TOKEN_LENGTH = 6;
    private static final String PREF_NAME = "airemote_auth";
    private static final String KEY_TOKEN = "pairing_token";

    private final SecureRandom random = new SecureRandom();
    private final android.content.Context context;
    private volatile String currentToken;

    public SessionAuth() {
        this(null);
    }

    public SessionAuth(android.content.Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
        if (this.context != null) {
            android.content.SharedPreferences prefs = this.context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE);
            String saved = prefs.getString(KEY_TOKEN, null);
            if (saved != null && saved.matches(TOKEN_REGEX)) {
                this.currentToken = saved;
            } else {
                regenerateToken();
            }
        } else {
            regenerateToken();
        }
    }

    public synchronized String regenerateToken() {
        char[] chars = new char[TOKEN_LENGTH];
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            chars[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        currentToken = new String(chars);
        if (context != null) {
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_TOKEN, currentToken).apply();
            } catch (Exception ignored) {}
        }
        return currentToken;
    }

    public String getCurrentToken() {
        return currentToken;
    }

    /**
     * Validates whether the provided token matches the active session token using constant-time comparison.
     */
    public boolean validate(String token) {
        if (token == null) return false;
        String trimmed = token.trim().toUpperCase();
        if (!trimmed.matches(TOKEN_REGEX) || currentToken == null) {
            return false;
        }

        byte[] a = currentToken.getBytes(StandardCharsets.UTF_8);
        byte[] b = trimmed.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
