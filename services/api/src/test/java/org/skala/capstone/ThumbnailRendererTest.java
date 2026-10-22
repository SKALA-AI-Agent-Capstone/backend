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

    @Test
    void unsupportedOrBrokenFilesGiveNoThumbnail() {
        byte[] webpHeader = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        byte[] brokenPng = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        assertThat(renderer.render(webpHeader, "image/webp")).isEmpty();
        assertThat(renderer.render(brokenPng, "image/png")).isEmpty();
        assertThat(renderer.render("%PDF-1.7 broken".getBytes(), "application/pdf")).isEmpty();
    }
}
