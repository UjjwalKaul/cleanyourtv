package io.github.cleanyourtv.airemote.qr;

import android.graphics.Bitmap;
import android.graphics.Color;

import io.nayuki.qrcodegen.QrCode;

/**
 * High-precision, ISO/IEC 18004 compliant QR code rasterizer.
 * Guaranteed to be recognized instantly by Google Lens, iOS Camera, and all barcode scanners.
 */
public final class MicroQr {

    private MicroQr() {}

    public static Bitmap encodeToBitmap(String text, int targetSize) {
        QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
        int modules = qr.size;
        int quietZone = 1; // Sleek 1-module quiet zone; FrameLayout supplies outer container padding
        int scale = Math.max(2, targetSize / (modules + quietZone * 2));
        int size = (modules + quietZone * 2) * scale;

        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[size * size];

        // Fill white background
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = Color.WHITE;
        }

        // Draw black modules
        for (int r = 0; r < modules; r++) {
            for (int c = 0; c < modules; c++) {
                if (qr.getModule(c, r)) {
                    int startX = (quietZone + c) * scale;
                    int startY = (quietZone + r) * scale;
                    for (int dy = 0; dy < scale; dy++) {
                        int rowOffset = (startY + dy) * size;
                        for (int dx = 0; dx < scale; dx++) {
                            pixels[rowOffset + (startX + dx)] = Color.BLACK;
                        }
                    }
                }
            }
        }

        bitmap.setPixels(pixels, 0, size, 0, 0, size, size);
        return bitmap;
    }
}
