package org.skala.capstone.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewAssetService {
    private final AssetRepository repository;
    private final StringRedisTemplate cache;

    public ReviewAssetService(AssetRepository repository,StringRedisTemplate cache) {
        this.repository=repository;this.cache=cache;
    }
    public static String hash(byte[] raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception e){throw new IllegalStateException(e);}}
    static String assetKey(String id){return "review:asset:"+id;}
    String mime(byte[] bytes){
        if(bytes.length>5 && new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))return "application/pdf";
        if(bytes.length>8 && bytes[0]==(byte)137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71)return "image/png";
        if(bytes.length>3 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255)return "image/jpeg";
        if(bytes.length>12 && new String(bytes,0,4,StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes,8,4,StandardCharsets.US_ASCII).equals("WEBP"))return "image/webp";
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"PDF_OR_IMAGE_REQUIRED");
    }

    public void store(String assetId,String reviewId,int no,String name,String mime,String role,String docType,byte[] raw) {
        repository.create(assetId,reviewId,no,name,mime,role,docType,raw,hash(raw));
        cache.opsForValue().set(assetKey(assetId),Base64.getEncoder().encodeToString(raw),Duration.ofMinutes(30));
    }

    public void discardCached(String assetId) {
        cache.delete(assetKey(assetId));
    }

    public PersistedAsset readRunAsset(String reviewId,int no,String assetId) {
        var rows=repository.scopedMime(reviewId,no,assetId);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND");
        String value=readCachedAsset(assetId);
        if(value==null)throw new ResponseStatusException(HttpStatus.GONE,"ORIGINAL_EXPIRED_REUPLOAD_REQUIRED");
        return new PersistedAsset(Base64.getDecoder().decode(value),rows.getFirst().get("mime_type").toString());
    }

    /** Redis는 분석 중 빠른 조회용 핫 캐시일 뿐이다. 원본은 app.review_assets.bytes에 영구 보존되므로
     * 이 호출은 캐시 메모리만 비우고 원본을 지우지 않는다. */
    public void purge(String id,int no){for(var row:repository.ids(id,no))cache.delete(assetKey(row.get("id").toString()));}
    public String readCachedAsset(String id) { return cache.opsForValue().get(assetKey(id)); }
    /** 사용자 화면(위반 검토, 리포트)에서 보는 원본. 같은 기업 소유인지 확인한 뒤 영구 저장소에서 읽는다. */
    public record PersistedAsset(byte[] bytes,String mimeType){}
    public PersistedAsset readPersistedAsset(String tenant,String reviewId,String assetId){
        var rows=repository.persisted(tenant,reviewId,assetId);
        if(rows.isEmpty() || rows.getFirst().get("bytes")==null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND");
        return new PersistedAsset((byte[])rows.getFirst().get("bytes"),rows.getFirst().get("mime_type").toString());
    }
}
