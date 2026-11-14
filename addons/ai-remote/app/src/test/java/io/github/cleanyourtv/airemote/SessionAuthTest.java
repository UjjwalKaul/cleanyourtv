package io.github.cleanyourtv.airemote;

import org.junit.Test;
import static org.junit.Assert.*;

import io.github.cleanyourtv.airemote.server.SessionAuth;

public class SessionAuthTest {

    @Test
    public void testTokenGenerationAndValidation() {
        SessionAuth auth = new SessionAuth();
        String token = auth.getCurrentToken();
        assertNotNull(token);
        assertEquals(6, token.length());
        assertTrue(token.matches(SessionAuth.TOKEN_REGEX));

        // Exact match
        assertTrue(auth.validate(token));
        // Case-insensitive & trimmed match
        assertTrue(auth.validate(token.toLowerCase()));
        assertTrue(auth.validate("  " + token + "  "));

        // Invalid tokens
        assertFalse(auth.validate("WRONG1"));
        assertFalse(auth.validate(""));
        assertFalse(auth.validate(null));
        assertFalse(auth.validate("SHORT"));
        assertFalse(auth.validate("TOOLONG77"));
    }

    @Test
    public void testRegenerateToken() {
        SessionAuth auth = new SessionAuth();
        String t1 = auth.getCurrentToken();
        String t2 = auth.regenerateToken();
        assertNotNull(t2);
        assertTrue(auth.validate(t2));
        assertFalse(auth.validate(t1)); // Old token no longer valid
    }
}
