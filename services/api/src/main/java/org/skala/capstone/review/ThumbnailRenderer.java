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
 * 목록 화면 썸네일(너비 160px JPEG)을 만든다. 화면은 광고의 위쪽만 보여 주므로 높이는 240px까지 위에서부터 자른다.
 * 큰 사진을 통째로 풀지 않도록 이미지는 위쪽 영역만 목표의 약 2배 크기로 줄여 읽고, PDF는 첫 쪽만 낮은 해상도로 그린다.
 */
@Component
public class ThumbnailRenderer {
    static final int WIDTH = 160;
    static final int MAX_HEIGHT = 240;
    private static final float QUALITY = 0.8f;
    private static final float MIN_DPI = 18f;
    private static final float MAX_DPI = 72f;

    /** 만들 수 없는 형식(WebP, CMYK JPEG 등)이나 손상, 암호 걸린 파일이면 빈 값이다. */
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
