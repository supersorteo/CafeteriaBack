package Cafeteria.media;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.springframework.stereotype.Component;

import Cafeteria.shared.ApiException;
import jakarta.annotation.PostConstruct;

@Component
public class ImageProcessor {

    public record Processed(byte[] data, String mimeType, int width, int height) {}

    static final long MAX_PIXELS = 20_000_000L;
    private static final Set<String> ALLOWED_FORMATS = Set.of("jpeg", "png", "webp");

    @PostConstruct
    void configure() {
        ImageIO.setUseCache(false);
    }

    public Processed process(byte[] content) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                if (!ALLOWED_FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw invalid();
                }
                reader.setInput(input, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width * height > MAX_PIXELS) {
                    throw ApiException.badRequest("invalid_image", "La imagen supera los 20 megapíxeles permitidos");
                }
                return reencode(reader.read(0));
            } finally {
                reader.dispose();
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
    }

    private Processed reencode(BufferedImage source) throws IOException {
        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(),
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            if (!alpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
            }
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (alpha) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("Sin escritor PNG");
            }
            return new Processed(out.toByteArray(), "image/png", image.getWidth(), image.getHeight());
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.88f);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return new Processed(out.toByteArray(), "image/jpeg", image.getWidth(), image.getHeight());
    }

    private static ApiException invalid() {
        return ApiException.badRequest("invalid_image", "El archivo no es una imagen JPEG, PNG o WebP válida");
    }
}
