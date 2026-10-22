package dev.aemdebug.browser;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;

/** Downscaling, JPEG encoding and numbered component overlays for screenshots (Java2D, headless). */
public final class ImageUtil {

    /** A rectangle in page CSS pixels with a label number. */
    public static final class Box {
        final int number;
        final double x;
        final double y;
        final double width;
        final double height;

        public Box(int number, double x, double y, double width, double height) {
            this.number = number;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    private static final Color[] PALETTE = {
        new Color(230, 25, 75), new Color(0, 130, 200), new Color(60, 180, 75), new Color(245, 130, 48),
        new Color(145, 30, 180), new Color(70, 140, 140), new Color(240, 50, 230), new Color(128, 128, 0)
    };

    private ImageUtil() {
    }

    public static byte[] toJpeg(byte[] image, int maxWidth, float quality) throws IOException {
        return annotate(image, List.of(), maxWidth, quality);
    }

    /** Draws numbered boxes (coordinates in source-image pixels) and returns a downscaled JPEG. */
    public static byte[] annotate(byte[] image, List<Box> boxes, int maxWidth, float quality) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
        if (source == null) {
            throw new IOException("Unsupported image format");
        }
        double scale = source.getWidth() > maxWidth ? (double) maxWidth / source.getWidth() : 1.0;
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
            for (Box box : boxes) {
                drawBox(g, box, scale, width, height);
            }
        } finally {
            g.dispose();
        }
        return writeJpeg(target, quality);
    }

    private static void drawBox(Graphics2D g, Box box, double scale, int imageWidth, int imageHeight) {
        int x = (int) Math.round(box.x * scale);
        int y = (int) Math.round(box.y * scale);
        int w = Math.max(2, (int) Math.round(box.width * scale));
        int h = Math.max(2, (int) Math.round(box.height * scale));
        if (x >= imageWidth || y >= imageHeight || x + w <= 0 || y + h <= 0) {
            return;
        }
        Color color = PALETTE[Math.floorMod(box.number, PALETTE.length)];
        g.setColor(color);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(x, y, w, h);
        String label = "#" + box.number;
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            FontMetrics metrics = g.getFontMetrics();
            int labelWidth = metrics.stringWidth(label) + 6;
            int labelHeight = metrics.getHeight();
            int lx = Math.max(0, Math.min(x, imageWidth - labelWidth));
            int ly = Math.max(0, y - labelHeight < 0 ? y : y - labelHeight);
            g.fillRect(lx, ly, labelWidth, labelHeight);
            g.setColor(Color.WHITE);
            g.drawString(label, lx + 3, ly + metrics.getAscent());
        } catch (RuntimeException | InternalError e) {
            // Fonts can be unavailable on minimal systems; the box alone is still useful.
        }
    }

    private static byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Pixel size of an image, or null if unreadable. */
    public static int[] size(byte[] image) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
            return img == null ? null : new int[] {img.getWidth(), img.getHeight()};
        } catch (IOException e) {
            return null;
        }
    }

    /** Crops a region (source pixels, padded) and returns a JPEG no wider than maxWidth. */
    public static byte[] crop(byte[] image, double x, double y, double width, double height, int padding, int maxWidth)
            throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(image));
        if (source == null) {
            throw new IOException("Unsupported image format");
        }
        int left = (int) Math.max(0, Math.floor(x) - padding);
        int top = (int) Math.max(0, Math.floor(y) - padding);
        int right = (int) Math.min(source.getWidth(), Math.ceil(x + width) + padding);
        int bottom = (int) Math.min(source.getHeight(), Math.ceil(y + height) + padding);
        if (right - left < 2 || bottom - top < 2) {
            throw new IOException("Region is outside the image or empty");
        }
        BufferedImage region = source.getSubimage(left, top, right - left, bottom - top);
        double scale = region.getWidth() > maxWidth ? (double) maxWidth / region.getWidth() : 1.0;
        int w = Math.max(1, (int) Math.round(region.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(region.getHeight() * scale));
        BufferedImage target = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(region, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return writeJpeg(target, 0.8f);
    }
}
