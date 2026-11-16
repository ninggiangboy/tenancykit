package dev.ngb.attribution.links.adapter.link.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.ngb.core.error.BadRequestException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Renders a link's public URL as a QR code on request, an SVG or a PNG, and never stores it.
 *
 * <p>Error correction is {@code M}, the level that survives a printed smear without making the code too dense for a
 * phone. The size is bounded so one request cannot ask for a gigapixel image.
 */
public class ZxingQrRenderer {

    public static final int MIN_SIZE = 128;
    public static final int MAX_SIZE = 1024;

    /** A PNG of the URL at the requested size. */
    public byte[] png(String content, int size) {
        BitMatrix matrix = matrix(content, size);
        java.awt.image.BufferedImage image = toImage(matrix);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not render a QR code", e);
        }
    }

    /** An SVG of the URL at the requested size, one rectangle per dark module. */
    public String svg(String content, int size) {
        BitMatrix matrix = matrix(content, size);
        StringBuilder svg = new StringBuilder(4096);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(matrix.getWidth())
                .append("\" height=\"").append(matrix.getHeight())
                .append("\" viewBox=\"0 0 ").append(matrix.getWidth()).append(' ').append(matrix.getHeight())
                .append("\" shape-rendering=\"crispEdges\">");
        svg.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>");
        svg.append("<path fill=\"#000000\" d=\"");
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    svg.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        svg.append("\"/></svg>");
        return svg.toString();
    }

    private BitMatrix matrix(String content, int size) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException("A QR code needs a URL.");
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new BadRequestException("A QR code size is between " + MIN_SIZE + " and " + MAX_SIZE + ".");
        }
        try {
            return new QRCodeWriter().encode(
                    content,
                    BarcodeFormat.QR_CODE,
                    size,
                    size,
                    Map.of(
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                            EncodeHintType.CHARACTER_SET, "UTF-8",
                            EncodeHintType.MARGIN, 2));
        } catch (WriterException e) {
            throw new BadRequestException("The QR code could not be generated.");
        }
    }

    private java.awt.image.BufferedImage toImage(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        return image;
    }
}
