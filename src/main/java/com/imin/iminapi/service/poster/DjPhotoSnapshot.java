package com.imin.iminapi.service.poster;

import com.imin.iminapi.dto.StyleReferencePart;

/**
 * The resolved per-event DJ photo for ONE generation run: bytes are downloaded once, eagerly,
 * before the variant futures are submitted (the consistency boundary against a concurrent photo
 * replace/delete). Transient — only {@code url} is persisted (poster_generations.dj_photo_url);
 * bytes never enter any JSON column.
 */
public record DjPhotoSnapshot(String url, byte[] bytes, String mimeType) {

    /** The multipart part sent as Ideogram's character_reference_images. */
    public StyleReferencePart toPart() {
        String ext = "image/png".equals(mimeType) ? "png" : "jpg";
        return new StyleReferencePart(bytes, "dj-photo." + ext, mimeType);
    }
}
