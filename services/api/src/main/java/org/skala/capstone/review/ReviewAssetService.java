package org.skala.capstone.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewAssetService {
    private static final Logger log = LoggerFactory.getLogger(ReviewAssetService.class);
    private final AssetRepository repository;
    private final StringRedisTemplate cache;
    private final AtomicLong unavailableUntil = new AtomicLong();

    public ReviewAssetService(AssetRepository repository, StringRedisTemplate cache) {
        this.repository = repository;
        this.cache = cache;
    }

    public static String hash(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static String assetKey(String id) {
        return "review:asset:" + id;
    }

    public static String mime(byte[] bytes) {
        if (bytes.length > 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if (bytes.length > 8 && bytes[0] == (byte)137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71) return "image/png";
        if (bytes.length > 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255) return "image/jpeg";
        if (bytes.length > 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PDF_OR_IMAGE_REQUIRED");
    }

    public void store(String assetId, String reviewId, int version, String name, String mime, String role, String docType, byte[] raw) {
        repository.create(assetId, reviewId, version, name, mime, role, docType, raw, hash(raw));
        // 접수 응답은 Redis를 기다리지 않는다. 커밋 후 첫 조회에서 캐시를 채운다.
    }

    private boolean cacheAvailable() {
        long until = unavailableUntil.get();
        return until == 0 || System.nanoTime() - until >= 0;
    }

    private void cacheUnavailable(String id, RuntimeException failure) {
        unavailableUntil.set(System.nanoTime() + Duration.ofSeconds(30).toNanos());
        log.warn("Asset cache bypassed for 30 seconds after {} ({})", id, failure.getClass().getSimpleName());
    }

    private String cached(String id) {
        if (!cacheAvailable()) return null;
        try {
            return cache.opsForValue().get(assetKey(id));
        } catch (RuntimeException unavailable) {
            cacheUnavailable(id, unavailable);
            return null;
        }
    }

    private void cache(String id, byte[] bytes) {
        if (!cacheAvailable()) return;
        try {
            cache.opsForValue().set(assetKey(id), Base64.getEncoder().encodeToString(bytes), Duration.ofMinutes(30));
        } catch (RuntimeException unavailable) {
            cacheUnavailable(id, unavailable);
        }
    }

    public PersistedAsset readRunAsset(String reviewId, int version, String assetId) {
        // 캐시와 DB 대체 조회에 동일한 review 및 version 범위를 적용한다.
        var rows = repository.scopedMime(reviewId, version, assetId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND");
        String mime = rows.getFirst().get("mime_type").toString();
        String value = cached(assetId);
        try {
            if (value != null) return new PersistedAsset(Base64.getDecoder().decode(value), mime);
        } catch (IllegalArgumentException corrupt) {
            log.warn("Invalid cached original ignored for {}", assetId);
        }
        var originals = repository.scopedOriginal(reviewId, version, assetId);
        if (originals.isEmpty() || originals.getFirst().get("bytes") == null) {
            throw new ResponseStatusException(HttpStatus.GONE, "ORIGINAL_EXPIRED_REUPLOAD_REQUIRED");
        }
        byte[] bytes = (byte[]) originals.getFirst().get("bytes");
        cache(assetId, bytes);
        return new PersistedAsset(bytes, mime);
    }

    /** 처리 종료에는 Redis 캐시만 비운다. DB 원본 보존 정책은 그대로 유지한다. */
    public void purge(String reviewId, int version) {
        if (!cacheAvailable()) return;
        try {
            for (var row : repository.ids(reviewId, version)) {
                try {
                    cache.delete(assetKey(row.get("id").toString()));
                } catch (RuntimeException unavailable) {
                    cacheUnavailable(reviewId, unavailable);
                    return;
                }
            }
        } catch (RuntimeException unavailable) {
            log.warn("Asset cache cleanup deferred to TTL for review {}", reviewId);
        }
    }

    public record PersistedAsset(byte[] bytes, String mimeType) {}

    public PersistedAsset readPersistedAsset(String tenant, String reviewId, String assetId) {
        var rows = repository.persisted(tenant, reviewId, assetId);
        if (rows.isEmpty() || rows.getFirst().get("bytes") == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND");
        }
        return new PersistedAsset((byte[]) rows.getFirst().get("bytes"), rows.getFirst().get("mime_type").toString());
    }
}
