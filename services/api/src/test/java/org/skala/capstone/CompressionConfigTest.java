package org.skala.capstone;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** JSON 응답은 압축하고, SSE는 이벤트가 버퍼에 묶이지 않게 압축 대상에서 뺀다. */
@SpringBootTest(classes = CompressionConfigTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CompressionConfigTest {
    @EnableConfigurationProperties(ServerProperties.class)
    static class Config {}

    @Autowired
    private ServerProperties server;

    @Test
    void compressesJsonButNotEventStreams() {
        var compression = server.getCompression();
        assertThat(compression.getEnabled()).isTrue();
        assertThat(compression.getMimeTypes()).contains("application/json").doesNotContain("text/event-stream");
    }
}
