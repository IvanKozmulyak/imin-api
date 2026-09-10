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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
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

    /** Back-compat overload: an upload that makes no AI-provenance claim. */
    @Transactional
    public MediaUploadResponse upload(AuthPrincipal p, UUID eventId, MediaKind kind,
                                      byte[] bytes, String contentType, String originalFilename) {
        return upload(p, eventId, kind, bytes, contentType, originalFilename, null);
    }

    /** Back-compat overload: no rights attestation supplied. */
    @Transactional
    public MediaUploadResponse upload(AuthPrincipal p, UUID eventId, MediaKind kind,
                                      byte[] bytes, String contentType, String originalFilename,
                                      Boolean aiGenerated) {
        return upload(p, eventId, kind, bytes, contentType, originalFilename, aiGenerated, null);
    }

    /**
     * @param aiGenerated POSTER only. {@code TRUE} when the caller (the Poster
     *        Studio) is uploading an image it generated; anything else means the
     *        organizer's own file. Ignored for other media kinds — there is no
     *        column to put it in, and inventing one from a query parameter would
     *        be worse than dropping it.
     */
    @Transactional
    public MediaUploadResponse upload(AuthPrincipal p, UUID eventId, MediaKind kind,
                                      byte[] bytes, String contentType, String originalFilename,
                                      Boolean aiGenerated, Boolean rightsAttested) {
        Event e = loadOwned(p, eventId);
        // Rights gate BEFORE validation or any write: a DJ photo is a third
        // party's face on its way into an AI pipeline (Ideogram character
        // reference, OpenRouter vision gate). Droit à l'image (C. civ. 9) and
        // CPI L122-4 make that the uploader's claim to make, and refusing the
        // upload is the only point at which it can still be captured. Same shape
        // as the audience CSV import's consent attestation.
        if (kind == MediaKind.DJ_PHOTO && !Boolean.TRUE.equals(rightsAttested)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.RIGHTS_ATTESTATION_REQUIRED,
                    "You must confirm you hold the rights to this image and the consent of "
                            + "anyone depicted (rightsAttested=true)");
        }
        validate(kind, bytes, contentType);
        Integer durationSec = null;
        if (kind == MediaKind.VIDEO) {
            // Best-effort metadata only — do NOT gate the upload on it. The jcodec
            // probe returns null for many perfectly valid MP4s (box orderings/brands
            // it doesn't support), so rejecting on null would bounce real files. The
            // format is already validated via content-type + ftyp magic bytes in
            // validate(); a null here just means "duration unknown".
            durationSec = videoMetadata.probeMp4DurationSec(bytes);
        }
        // Hash the bytes into the key so each unique upload gets a unique URL.
        // Two reasons this matters: (1) R2 serves objects with Cache-Control: immutable,
        // so a re-upload at the same URL would be invisible to browsers indefinitely;
        // (2) identical content collapses to the same key (idempotent retries, dedup).
        String hash = contentHash(bytes);
        String key = "events/" + e.getId() + "/" + kind.wireValue() + "-" + hash
                + "." + extensionFor(contentType, originalFilename);
        String url = storage.urlFor(key);
        String oldUrl = switch (kind) {
            case POSTER -> e.getPosterUrl();
            case VIDEO -> e.getVideoUrl();
            case DJ_PHOTO -> e.getDjPhotoUrl();
        };
        switch (kind) {
            case POSTER -> {
                e.setPosterUrl(url);
                // Provenance (V71 / AI Act Art.50). A multipart upload is the organizer's
                // own asset unless the uploader says otherwise: the Poster Studio pushes
                // AI output through this same endpoint, and the bytes alone cannot tell
                // the two apart. Absent or false keeps the original meaning.
                e.setPosterAiGenerated(Boolean.TRUE.equals(aiGenerated));
            }
            case VIDEO -> e.setVideoUrl(url);
            case DJ_PHOTO -> {
                e.setDjPhotoUrl(url);
                // Stamped with the wording version, not just the time: a timestamp
                // without the text that was agreed to proves nothing later.
                e.setDjPhotoRightsAttestedAt(Instant.now());
                e.setDjPhotoRightsAttestationVersion(RightsAttestation.CURRENT_VERSION);
            }
        }
        events.save(e);
        // Upload to remote storage — if this throws, the DB row already has the correct URL
        // (the object simply won't exist yet; a retry of the same file will re-upload to the
        // same key because the hash is deterministic).
        MediaStorage.Stored stored = storage.put(key, bytes, contentType);
        // Best-effort cleanup of the previously stored object. Only after the new put
        // succeeded — never delete the old object before the new one is durable.
        if (oldUrl != null && !oldUrl.equals(url)) {
            String oldKey = storage.keyFor(oldUrl);
            if (oldKey != null && !oldKey.equals(key) && isOwnUploadKey(e.getId(), oldKey)) {
                try { storage.delete(oldKey); } catch (Exception ignored) {}
            }
        }
        return new MediaUploadResponse(stored.url(), stored.sizeBytes(), stored.contentType(), durationSec);
    }

    @Transactional
    public void delete(AuthPrincipal p, UUID eventId, MediaKind kind) {
        Event e = loadOwned(p, eventId);
        String url = switch (kind) {
            case POSTER -> e.getPosterUrl();
            case VIDEO -> e.getVideoUrl();
            case DJ_PHOTO -> e.getDjPhotoUrl();
        };
        if (url == null) return;
        String key = storage.keyFor(url);
        if (key != null && isOwnUploadKey(e.getId(), key)) {
            try { storage.delete(key); } catch (Exception ignored) {}
        }
        switch (kind) {
            case POSTER -> {
                e.setPosterUrl(null);
                e.setPosterAiGenerated(null); // no poster → no provenance claim (V71)
            }
            case VIDEO -> e.setVideoUrl(null);
            case DJ_PHOTO -> {
                e.setDjPhotoUrl(null);
                // No photo, no attestation: the record described an image that is gone.
                e.setDjPhotoRightsAttestedAt(null);
                e.setDjPhotoRightsAttestationVersion(null);
            }
        }
        events.save(e);
    }

    /**
     * True only for objects this event's own multipart uploads wrote, i.e. keys under
     * {@code events/{eventId}/}.
     *
     * <p>{@code MediaStorage.keyFor} resolves a key for ANY URL under the bucket's public
     * prefix, and the destructive delete used to fire on whatever it returned. AI posters
     * are written through the same bucket under the shared {@code ai-posters/} prefix
     * ({@code PosterImageStorage.AI_POSTER_KEY_PREFIX}) and are referenced by the concept
     * gallery, by every event promoted from that concept, and by already-sent emails and
     * social posts — and a URL can equally belong to another event. Deleting either is
     * unrecoverable; leaving an object behind costs bounded storage. So anything outside
     * this event's own namespace is left alone.
     */
    private static boolean isOwnUploadKey(UUID eventId, String key) {
        return key.startsWith("events/" + eventId + "/");
    }

    private Event loadOwned(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    /**
     * The per-kind size ceiling, split out so the controller can apply it to
     * {@code MultipartFile.getSize()} BEFORE {@code getBytes()} copies the part onto the heap.
     *
     * <p>{@code spring.servlet.multipart.max-file-size} is 60MB because the VIDEO kind needs it,
     * so a POSTER — capped here at 5 MB — used to be fully materialised at up to 12x its own
     * limit, per concurrent request, only to be rejected. Same limits, consulted first;
     * {@link #validate} still calls this so the rule has one definition and the magic-byte and
     * dimension checks stay on the loaded bytes where they have to be.
     */
    public static void checkSizeLimit(MediaKind kind, long size) {
        switch (kind) {
            case POSTER, DJ_PHOTO -> {
                if (size > 5 * MB) throw fieldErr("file", "must be ≤ 5 MB");
            }
            case VIDEO -> {
                if (size > 50 * MB) throw fieldErr("file", "must be ≤ 50 MB");
            }
        }
    }

    private static void validate(MediaKind kind, byte[] bytes, String contentType) {
        checkSizeLimit(kind, bytes.length);
        switch (kind) {
            case POSTER, DJ_PHOTO -> {
                if (!IMAGE_TYPES.contains(contentType)) throw fieldErr("file", "must be JPG or PNG");
            }
            case VIDEO -> {
                if (!VIDEO_TYPES.contains(contentType)) throw fieldErr("file", "must be MP4");
            }
        }
        verifyMagicBytes(bytes, contentType);
        if (kind == MediaKind.DJ_PHOTO) {
            verifyDjPhotoDimensions(bytes);
        }
    }

    private static void verifyMagicBytes(byte[] bytes, String contentType) {
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

    /** A face needs resolution for the character reference: decodable, short side ≥ 256 px. */
    private static void verifyDjPhotoDimensions(byte[] bytes) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw fieldErr("file", "could not decode image");
        }
        if (img == null) throw fieldErr("file", "could not decode image");
        if (Math.min(img.getWidth(), img.getHeight()) < 256) {
            throw fieldErr("file", "must be at least 256px on the short side");
        }
    }

    private static ApiException fieldErr(String field, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID,
                "Invalid file", Map.of(field, msg));
    }

    private static String contentHash(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            // 8 bytes = 16 hex chars = 64 bits of entropy. Ample for collision-free per-event keys.
            byte[] prefix = new byte[8];
            System.arraycopy(digest, 0, prefix, 0, 8);
            return HexFormat.of().formatHex(prefix);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is required to be present on every JRE.
            throw new IllegalStateException(ex);
        }
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
