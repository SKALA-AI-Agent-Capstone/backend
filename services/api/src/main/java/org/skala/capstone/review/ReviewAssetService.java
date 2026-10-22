package org.skala.capstone.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewAssetService {
    private static final Logger log = LoggerFactory.getLogger(ReviewAssetService.class);
    private final AssetRepository repository;
    private final StringRedisTemplate cache;

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

    String mime(byte[] bytes) {
        if (bytes.length > 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if (bytes.length > 8 && bytes[0] == (byte)137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71) return "image/png";
        if (bytes.length > 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255) return "image/jpeg";
        if (bytes.length > 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PDF_OR_IMAGE_REQUIRED");
    }

    public void store(String assetId, String reviewId, int version, String name, String mime, String role, String docType, byte[] raw) {
        repository.create(assetId, reviewId, version, name, mime, role, docType, raw, hash(raw));
        // 롤백된 원본은 캐시에 쓰지 않는다. 캐시 장애는 접수 커밋을 실패시키지 않는다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    cache(assetId, raw);
                }
            });
        } else {
            cache(assetId, raw);
        }
    }

    private void cache(String id, byte[] bytes) {
        try {
            cache.opsForValue().set(assetKey(id), Base64.getEncoder().encodeToString(bytes), Duration.ofMinutes(30));
        } catch (RuntimeException unavailable) {
            log.warn("Asset cache write skipped for {} ({})", id, unavailable.getClass().getSimpleName());
        }
    }

    public PersistedAsset readRunAsset(String reviewId, int version, String assetId) {
        // 캐시와 DB 대체 조회에 동일한 review 및 version 범위를 적용한다.
        var rows = repository.scopedMime(reviewId, version, assetId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND");
        String mime = rows.getFirst().get("mime_type").toString();
        try {
            String value = cache.opsForValue().get(assetKey(assetId));
            if (value != null) return new PersistedAsset(Base64.getDecoder().decode(value), mime);
        } catch (RuntimeException unavailable) {
            log.warn("Asset cache read skipped for {} ({})", assetId, unavailable.getClass().getSimpleName());
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
        try {
            for (var row : repository.ids(reviewId, version)) {
                try {
                    cache.delete(assetKey(row.get("id").toString()));
                } catch (RuntimeException unavailable) {
                    log.warn("Asset cache cleanup deferred to TTL for review {}", reviewId);
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
