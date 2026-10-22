package org.skala.capstone.review;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 목록 화면 썸네일. 처음 요청할 때 원본으로 만들어 review_assets.thumbnail에 저장하고, 다음부터는 저장본을 돌려준다.
 * 만들 수 없는 원본이면 저장하지 않고 404 THUMBNAIL_UNAVAILABLE을 돌려주며, 화면은 형식 아이콘으로 대신한다.
 */
@Service
public class AssetThumbnailService {
    private final AssetRepository repository;
    private final ThumbnailRenderer renderer;

    public AssetThumbnailService(AssetRepository repository, ThumbnailRenderer renderer) {
        this.repository = repository;
        this.renderer = renderer;
    }

    public byte[] thumbnail(String tenant, String reviewId, String assetId) {
        var rows = repository.thumbnailOrOriginal(tenant, reviewId, assetId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND");
        }
        var row = rows.getFirst();
        if (row.get("thumbnail") instanceof byte[] stored) return stored;
        if (!(row.get("bytes") instanceof byte[] original)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "THUMBNAIL_UNAVAILABLE");
        }
        byte[] created =
                renderer.render(original, row.get("mime_type").toString())
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "THUMBNAIL_UNAVAILABLE"));
        // 동시에 만든 요청이 있으면 먼저 저장된 것을 그대로 둔다. 둘 다 같은 원본에서 만들어 내용은 같다.
        repository.saveThumbnail(assetId, created);
        return created;
    }
}
