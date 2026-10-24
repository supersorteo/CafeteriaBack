package Cafeteria.support;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.imageio.ImageIO;

public final class TestImages {

    private static final String TINY_WEBP = "UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

    private TestImages() {}

    public static byte[] jpeg(int width, int height) {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
    }

    public static byte[] png(int width, int height, boolean alpha) {
        return encode(new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB), "png");
    }

    public static byte[] hugeBinaryPng(int width, int height) {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY), "png");
    }

    public static byte[] webp() {
        return Base64.getDecoder().decode(TINY_WEBP);
    }

    public static byte[] jpegWithExif() {
        byte[] base = jpeg(16, 16);
        byte[] exif = "Exif\0\0SECRETO-GPS".getBytes(StandardCharsets.ISO_8859_1);
        int length = exif.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(base, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(exif, 0, exif.length);
        out.write(base, 2, base.length - 2);
        return out.toByteArray();
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("Sin escritor para " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
