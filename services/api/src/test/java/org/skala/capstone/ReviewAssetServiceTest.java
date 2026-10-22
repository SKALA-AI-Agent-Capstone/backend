package org.skala.capstone;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.skala.capstone.review.AssetRepository;
import org.skala.capstone.review.ReviewAssetService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

class ReviewAssetServiceTest {
    private final AssetRepository repository=mock(AssetRepository.class);
    private final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String,String> values=mock(ValueOperations.class);
    private final ReviewAssetService assets=new ReviewAssetService(repository,redis);
    private final byte[] bytes={1,2,3};

    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(repository.scopedMime("review",1,"asset")).thenReturn(List.of(Map.of("mime_type","image/png")));
        when(repository.scopedOriginal("review",1,"asset")).thenReturn(List.of(Map.of("bytes",bytes)));
    }

    @Test void cacheMissLoadsTheScopedOriginalAndRefillsCache() {
        assertArrayEquals(bytes,assets.readRunAsset("review",1,"asset").bytes());
        verify(values).set(eq("review:asset:asset"),eq(Base64.getEncoder().encodeToString(bytes)),any());
    }

    @Test void validCacheAvoidsReadingOriginalBytesFromDatabase() {
        when(values.get(anyString())).thenReturn(Base64.getEncoder().encodeToString(bytes));
        assertArrayEquals(bytes,assets.readRunAsset("review",1,"asset").bytes());
        verify(repository,never()).scopedOriginal(anyString(),anyInt(),anyString());
    }

    @Test void corruptCacheFallsBackToOriginal() {
        when(values.get(anyString())).thenReturn("!invalid-base64!");
        assertArrayEquals(bytes,assets.readRunAsset("review",1,"asset").bytes());
    }

    @Test void wrongRunScopeIsRejectedBeforeAccessingCache() {
        when(repository.scopedMime("other",1,"asset")).thenReturn(List.of());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->assets.readRunAsset("other",1,"asset")).getStatusCode().value());
        verifyNoInteractions(redis);
    }

    @Test void legacyAssetWithNeitherCacheNorOriginalStillRequiresReupload() {
        Map<String,Object> missing=new HashMap<>();
        missing.put("bytes",null);
        when(repository.scopedOriginal("review",1,"asset")).thenReturn(List.of(missing));
        assertEquals(410,assertThrows(ResponseStatusException.class,()->assets.readRunAsset("review",1,"asset")).getStatusCode().value());
    }
}
