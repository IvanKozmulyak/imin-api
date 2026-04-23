package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.MediaUploadResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.storage.MediaStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MediaUploadService {

    private static final long MB = 1024L * 1024L;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/jpg");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4");

    private final EventRepository events;
    private final MediaStorage storage;
    private final VideoMetadata videoMetadata;

    public MediaUploadService(EventRepository events, MediaStorage storage, VideoMetadata videoMetadata) {
        this.events = events;
        this.storage = storage;
        this.videoMetadata = videoMetadata;
    }

    @Transactional
    public MediaUploadResponse upload(AuthPrincipal p, UUID eventId, MediaKind kind,
                                      byte[] bytes, String contentType, String originalFilename) {
        Event e = loadOwned(p, eventId);
        validate(kind, bytes, contentType);
        Integer durationSec = null;
        if (kind == MediaKind.VIDEO) {
            durationSec = videoMetadata.probeMp4DurationSec(bytes);
            if (durationSec == null || durationSec > 30) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                        "Video must be a parseable MP4 ≤ 30 seconds", Map.of("file", "duration > 30s"));
            }
        }
        String key = "events/" + e.getId() + "/" + kind.wireValue() + "." + extensionFor(contentType, originalFilename);
        // Compute the deterministic URL before any remote call, then persist the DB row first.
        // If storage.put fails after the save, no orphan is created in remote storage.
        String url = storage.urlFor(key);
        switch (kind) {
            case POSTER -> e.setPosterUrl(url);
            case VIDEO -> e.setVideoUrl(url);
            case COVER -> e.setCoverUrl(url);
        }
        events.save(e);
        // Upload to remote storage — if this throws, the DB row already has the correct URL
        // (the object simply won't exist yet; a retry will re-upload).
        MediaStorage.Stored stored = storage.put(key, bytes, contentType);
        return new MediaUploadResponse(stored.url(), stored.sizeBytes(), stored.contentType(), durationSec);
    }

    @Transactional
    public void delete(AuthPrincipal p, UUID eventId, MediaKind kind) {
        Event e = loadOwned(p, eventId);
        String url = switch (kind) {
            case POSTER -> e.getPosterUrl();
            case VIDEO -> e.getVideoUrl();
            case COVER -> e.getCoverUrl();
        };
        if (url == null) return;
        // Reverse-derive key from public URL (everything after the prefix).
        String key = "events/" + e.getId() + "/" + kind.wireValue() + "." + url.substring(url.lastIndexOf('.') + 1);
        try { storage.delete(key); } catch (Exception ignored) {}
        switch (kind) {
            case POSTER -> e.setPosterUrl(null);
            case VIDEO -> e.setVideoUrl(null);
            case COVER -> e.setCoverUrl(null);
        }
        events.save(e);
    }

    private Event loadOwned(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    private static void validate(MediaKind kind, byte[] bytes, String contentType) {
        long size = bytes.length;
        switch (kind) {
            case POSTER, COVER -> {
                if (size > 5 * MB) throw fieldErr("file", "must be ≤ 5 MB");
                if (!IMAGE_TYPES.contains(contentType)) throw fieldErr("file", "must be JPG or PNG");
            }
            case VIDEO -> {
                if (size > 50 * MB) throw fieldErr("file", "must be ≤ 50 MB");
                if (!VIDEO_TYPES.contains(contentType)) throw fieldErr("file", "must be MP4");
            }
        }
        verifyMagicBytes(kind, bytes, contentType);
    }

    private static void verifyMagicBytes(MediaKind kind, byte[] bytes, String contentType) {
        if (bytes.length < 8) {
            throw fieldErr("file", "content does not match declared type");
        }
        switch (contentType) {
            case "image/png" -> {
                if (!((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50
                        && (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47)) {
                    throw fieldErr("file", "content does not match declared type");
                }
            }
            case "image/jpeg", "image/jpg" -> {
                if (!((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8)) {
                    throw fieldErr("file", "content does not match declared type");
                }
            }
            case "video/mp4" -> {
                // bytes 4..7 must equal ASCII "ftyp"
                if (bytes.length < 8 || bytes[4] != 'f' || bytes[5] != 't'
                        || bytes[6] != 'y' || bytes[7] != 'p') {
                    throw fieldErr("file", "content does not match declared type");
                }
            }
            default -> { /* no magic-byte check for unknown types */ }
        }
    }

    private static ApiException fieldErr(String field, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                "Invalid file", Map.of(field, msg));
    }

    private static String extensionFor(String contentType, String originalFilename) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "video/mp4" -> "mp4";
            default -> {
                int dot = originalFilename.lastIndexOf('.');
                yield dot >= 0 ? originalFilename.substring(dot + 1).toLowerCase() : "bin";
            }
        };
    }
}
