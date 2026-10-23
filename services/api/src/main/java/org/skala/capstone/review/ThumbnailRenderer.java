package org.skala.capstone.review;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * 목록 화면 썸네일(너비 160px)을 만든다. 화면은 광고의 위쪽만 보여 주므로 높이는 240px까지 위에서부터 자른다.
 * 큰 사진을 통째로 풀지 않도록 이미지는 위쪽 영역만 목표의 약 2배 크기로 줄여 읽고, PDF는 첫 쪽만 낮은 해상도로 그린다.
 * 광고 썸네일은 JPEG(투명한 부분은 흰색), 제품 대표 이미지 썸네일은 배경을 지운 이미지가 많아 투명도를 지키는 PNG다.
 * WebP는 TwelveMonkeys 플러그인으로 읽는다.
 */
@Component
public class ThumbnailRenderer {
    static final int WIDTH = 160;
    static final int MAX_HEIGHT = 240;
    /** 제품 썸네일은 자르지 않고 긴 변을 이 길이에 맞춘다(화면 최대 112px의 2배 정도). */
    static final int PRODUCT_MAX_SIDE = 240;
    /**
     * 풀기 전에 머리에서 읽은 가로와 세로의 곱이 이보다 크면 썸네일을 만들지 않는다. 파일은 작아도 해상도가 큰 이미지가
     * 메모리를 넘기지 않게 막는다(Render 무료 512MB). 4천만 픽셀은 일반 사진(최대 수천만 화소)은 통과한다.
     */
    static final long MAX_PIXELS = 40_000_000L;
    private static final float QUALITY = 0.8f;
    private static final float MIN_DPI = 18f;
    private static final float MAX_DPI = 72f;

    static {
        // Spring Boot 실행 jar 안의 ImageIO 플러그인(WebP)은 자동으로 등록되지 않을 수 있어 한 번 다시 찾는다
        ImageIO.scanForPlugins();
    }

    /** 광고 썸네일(JPEG). 만들 수 없는 형식(CMYK JPEG 등)이나 손상, 암호 걸린 파일이면 빈 값이다. */
    public Optional<byte[]> render(byte[] original, String mime) {
        try {
            BufferedImage source =
                    "application/pdf".equals(mime) ? firstPdfPage(original) : imageTop(original);
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return Optional.empty();
            }
            return Optional.of(jpeg(fit(source)));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }


    /**
     * 제품 대표 이미지 썸네일(투명도를 지키는 PNG). 광고와 달리 위쪽만 자르지 않고 이미지 전체를 긴 변 240px에 맞춘다.
     * 만들 수 없으면 빈 값이다.
     */
    public Optional<byte[]> renderTransparent(byte[] original, String mime) {
        try {
            BufferedImage source = imageWhole(original);
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return Optional.empty();
            }
            return Optional.of(png(fitWithin(source, PRODUCT_MAX_SIDE)));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static BufferedImage imageTop(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (tooLarge(width, height)) return null;
                int regionHeight =
                        Math.min(height, (int) Math.ceil((double) width * MAX_HEIGHT / WIDTH));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceRegion(new Rectangle(0, 0, width, regionHeight));
                int step = Math.max(1, width / (WIDTH * 2));
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 이미지 전체를 목표의 약 2배 크기로 줄여 읽는다. */
    private static BufferedImage imageWhole(byte[] bytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (tooLarge(reader.getWidth(0), reader.getHeight(0))) return null;
                int longest = Math.max(reader.getWidth(0), reader.getHeight(0));
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, longest / (PRODUCT_MAX_SIDE * 2));
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 비율을 지키며 긴 변을 maxSide 이하로 줄인다(작은 이미지는 키우지 않는다). 투명도를 유지한다. */
    static BufferedImage fitWithin(BufferedImage source, int maxSide) {
        double scale = Math.min(1.0, (double) maxSide / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = out.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return out;
    }

    public static boolean tooLarge(int width, int height) {
        return (long) width * height > MAX_PIXELS;
    }

    private static BufferedImage firstPdfPage(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() == 0) return null;
            PDRectangle box = document.getPage(0).getCropBox();
            float dpi = WIDTH * 2 * 72f / Math.max(1f, box.getWidth());
            dpi = Math.max(MIN_DPI, Math.min(MAX_DPI, dpi));
            return new PDFRenderer(document).renderImageWithDPI(0, dpi, ImageType.RGB);
        }
    }

    static BufferedImage fit(BufferedImage source) {
        int height =
                Math.max(
                        1,
                        Math.min(
                                MAX_HEIGHT,
                                Math.round((float) source.getHeight() * WIDTH / source.getWidth())));
        int sourceHeight =
                Math.min(
                        source.getHeight(),
                        Math.max(1, Math.round((float) height * source.getWidth() / WIDTH)));
        BufferedImage out = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = out.createGraphics();
        try {
            // 투명한 부분은 흰 배경으로 채운다(JPEG에는 투명도가 없다).
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, WIDTH, height);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(
                    source, 0, 0, WIDTH, height, 0, 0, source.getWidth(), sourceHeight, null);
        } finally {
            graphics.dispose();
        }
        return out;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG_WRITER_MISSING");
        return bytes.toByteArray();
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(QUALITY);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
