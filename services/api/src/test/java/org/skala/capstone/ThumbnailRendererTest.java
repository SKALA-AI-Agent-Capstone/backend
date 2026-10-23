package org.skala.capstone;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.skala.capstone.review.ThumbnailRenderer;

/** 목록 썸네일: 너비 160px JPEG, 위쪽만 높이 240px까지, 투명은 흰색, 만들 수 없는 형식은 빈 값. */
class ThumbnailRendererTest {
    private final ThumbnailRenderer renderer = new ThumbnailRenderer();

    static byte[] png(int width, int height, Color top, Color bottom, boolean alpha) throws IOException {
        BufferedImage image =
                new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(top);
        graphics.fillRect(0, 0, width, height / 2);
        graphics.setColor(bottom);
        graphics.fillRect(0, height / 2, width, height - height / 2);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    static BufferedImage read(byte[] jpeg) throws IOException {
        // JPEG 시작 표식(FF D8)으로 형식을 확인한 뒤 픽셀을 읽는다.
        assertThat(jpeg[0]).isEqualTo((byte) 0xFF);
        assertThat(jpeg[1]).isEqualTo((byte) 0xD8);
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    @Test
    void wideImageBecomesA160PixelJpegKeepingItsRatio() throws IOException {
        BufferedImage thumb =
                read(renderer.render(png(2000, 1000, Color.RED, Color.BLUE, false), "image/png").orElseThrow());
        assertThat(thumb.getWidth()).isEqualTo(160);
        assertThat(thumb.getHeight()).isEqualTo(80);
    }

    @Test
    void tallImageKeepsOnlyItsTopPart() throws IOException {
        // 위쪽 절반은 빨강, 아래쪽 절반은 파랑인 긴 세로 이미지. 썸네일에는 위쪽(빨강)만 남는다.
        BufferedImage thumb =
                read(renderer.render(png(800, 8000, Color.RED, Color.BLUE, false), "image/png").orElseThrow());
        assertThat(thumb.getWidth()).isEqualTo(160);
        assertThat(thumb.getHeight()).isEqualTo(240);
        Color bottomRow = new Color(thumb.getRGB(80, 235));
        assertThat(bottomRow.getRed()).isGreaterThan(200);
        assertThat(bottomRow.getBlue()).isLessThan(60);
    }

    @Test
    void transparentPixelsBecomeWhite() throws IOException {
        BufferedImage thumb =
                read(
                        renderer.render(png(400, 400, new Color(0, 0, 0, 0), new Color(0, 0, 0, 0), true), "image/png")
                                .orElseThrow());
        Color center = new Color(thumb.getRGB(80, 80));
        assertThat(center.getRed()).isGreaterThan(240);
        assertThat(center.getGreen()).isGreaterThan(240);
        assertThat(center.getBlue()).isGreaterThan(240);
    }

    @Test
    void pdfUsesTheFirstPageOnly() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage first = new PDPage(PDRectangle.A4);
            document.addPage(first);
            try (PDPageContentStream content = new PDPageContentStream(document, first)) {
                content.setNonStrokingColor(Color.RED);
                content.addRect(0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                content.fill();
            }
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(out);
        }
        BufferedImage thumb = read(renderer.render(out.toByteArray(), "application/pdf").orElseThrow());
        assertThat(thumb.getWidth()).isEqualTo(160);
        assertThat(thumb.getHeight()).isBetween(200, 240);
        assertThat(new Color(thumb.getRGB(80, 80)).getRed()).isGreaterThan(200);
    }

    /** 1x1 무손실 WebP. Java 기본 ImageIO는 못 읽고 TwelveMonkeys 플러그인으로 읽는다. */
    static final byte[] TINY_WEBP =
            java.util.Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

    @Test
    void webpImagesAreReadWithThePlugin() throws Exception {
        BufferedImage thumb = read(renderer.render(TINY_WEBP, "image/webp").orElseThrow());
        assertThat(thumb.getWidth()).isEqualTo(160);
    }

    @Test
    void productThumbnailsKeepTransparencyAsPng() throws Exception {
        BufferedImage cutout = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        var graphics = cutout.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(100, 100, 200, 200);
        graphics.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(cutout, "png", out);

        byte[] png = renderer.renderTransparent(out.toByteArray(), "image/png").orElseThrow();
        assertThat(png[1]).isEqualTo((byte) 'P');
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(thumb.getWidth()).isEqualTo(240); // 정사각 400px을 긴 변 240px로
        assertThat(thumb.getHeight()).isEqualTo(240);
        assertThat(thumb.getColorModel().hasAlpha()).isTrue();
        assertThat(thumb.getRGB(2, 2) >>> 24).isZero(); // 모서리는 투명
        assertThat(new Color(thumb.getRGB(120, 120), true).getAlpha()).isEqualTo(255);
        // 광고 썸네일은 예전처럼 JPEG이고 투명한 부분은 흰색이다
        BufferedImage ad = read(renderer.render(out.toByteArray(), "image/png").orElseThrow());
        assertThat(new Color(ad.getRGB(2, 2)).getRed()).isGreaterThan(240);
    }

    @Test
    void productThumbnailsShowTheWholeImageWithoutCropping() throws Exception {
        // 세로로 긴 제품 사진(300x900): 광고 썸네일처럼 위쪽만 자르지 않는다
        BufferedImage tall = new BufferedImage(300, 900, BufferedImage.TYPE_INT_ARGB);
        var graphics = tall.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 800, 300, 100); // 맨 아래 띠
        graphics.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(tall, "png", out);
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(
                renderer.renderTransparent(out.toByteArray(), "image/png").orElseThrow()));
        assertThat(thumb.getHeight()).isEqualTo(240);
        assertThat(thumb.getWidth()).isEqualTo(80);
        assertThat(new Color(thumb.getRGB(40, 235), true).getBlue()).isGreaterThan(200); // 아래 띠가 남아 있다
    }

    @Test
    void hugeResolutionsAreRefusedBeforeDecoding() throws Exception {
        assertThat(ThumbnailRenderer.tooLarge(8000, 5000)).isFalse(); // 4천만 픽셀까지는 만든다
        assertThat(ThumbnailRenderer.tooLarge(8000, 5001)).isTrue();
        assertThat(ThumbnailRenderer.tooLarge(30_000, 30_000)).isTrue(); // int 곱셈이면 넘쳐서 음수가 된다
        // 머리만 30000x30000인 PNG: 픽셀을 풀기 전에 거절한다(정상 PNG라면 3.6GB가 필요하다)
        byte[] huge = pngHeaderOnly(30_000, 30_000);
        assertThat(renderer.render(huge, "image/png")).isEmpty();
        assertThat(renderer.renderTransparent(huge, "image/png")).isEmpty();
    }

    static byte[] pngHeaderOnly(int width, int height) throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        var ihdr = java.nio.ByteBuffer.allocate(17).put("IHDR".getBytes())
                .putInt(width).putInt(height).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        var crc = new java.util.zip.CRC32();
        crc.update(ihdr.array());
        out.write(java.nio.ByteBuffer.allocate(4).putInt(13).array());
        out.write(ihdr.array());
        out.write(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
        return out.toByteArray();
    }

    @Test
    void unsupportedOrBrokenFilesGiveNoThumbnail() {
        byte[] webpHeader = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        byte[] brokenPng = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        assertThat(renderer.render(webpHeader, "image/webp")).isEmpty();
        assertThat(renderer.render(brokenPng, "image/png")).isEmpty();
        assertThat(renderer.render("%PDF-1.7 broken".getBytes(), "application/pdf")).isEmpty();
    }
}
