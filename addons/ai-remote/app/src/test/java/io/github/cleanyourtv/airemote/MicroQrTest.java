package io.github.cleanyourtv.airemote;

import org.junit.Test;
import static org.junit.Assert.*;

import io.nayuki.qrcodegen.QrCode;

public class MicroQrTest {

    @Test
    public void testEncodeStandardUrl() {
        String url = "http://192.168.1.50:8080/?token=K9X2B7";
        QrCode qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
        assertNotNull(qr);
        assertTrue(qr.size >= 21);

        // Standard finder pattern at top-left: module at (0,0) is black
        assertTrue(qr.getModule(0, 0));
        // Separator at (7, 0) is white
        assertFalse(qr.getModule(7, 0));
    }
}
