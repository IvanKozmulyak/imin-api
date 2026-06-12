package com.imin.iminapi.service.org;

import com.imin.iminapi.dto.org.LogoUploadResponse;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.storage.MediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Brand-logo upload, parallel to {@link com.imin.iminapi.service.event.MediaUploadService}: same R2
 * {@link MediaStorage} seam and URL-precompute-then-put retry-safe pattern. PNG-only (Phase 1),
 * ≤2 MB, min short side 128 px, aspect between 1:4 and 4:1. Key is content-addressed:
 * {@code orgs/{orgId}/brand/logo-{sha256prefix8}.png}. The old object is deleted best-effort only
 * after the new put succeeds (orphans accepted as known debt and logged).
 */
@Service
public class OrgMediaService {

    private static final Logger log = LoggerFactory.getLogger(OrgMediaService.class);
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final int MIN_SHORT_SIDE = 128;
    private static final double MIN_ASPECT = 0.25; // 1:4
    private static final double MAX_ASPECT = 4.0;  // 4:1

    private final OrganizationRepository orgs;
    private final MediaStorage storage;
    private final OrgBrandService brandService;

    public OrgMediaService(OrganizationRepository orgs, MediaStorage storage, OrgBrandService brandService) {
        this.orgs = orgs;
        this.storage = storage;
        this.brandService = brandService;
    }

    public LogoUploadResponse uploadLogo(AuthPrincipal p, byte[] bytes, String contentType, String originalFilename) {
        Organization o = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
        validate(bytes, contentType);

        String hash = contentHash(bytes);
        String key = "orgs/" + o.getId() + "/brand/logo-" + hash + ".png";
        String url = storage.urlFor(key);
        String oldUrl = o.getBrandLogoUrl();

        // Persist the URL first (retry-safe: if the put below throws, a retry re-puts to the same
        // deterministic key). setLogoUrl re-loads and saves the org in its own @Transactional.
        brandService.setLogoUrl(p, url);
        storage.put(key, bytes, contentType);

        // Best-effort cleanup of the previously stored object — only after the new put succeeded.
        if (oldUrl != null && !oldUrl.equals(url)) {
            String oldKey = storage.keyFor(oldUrl);
            if (oldKey != null && !oldKey.equals(key)) {
                try { storage.delete(oldKey); }
                catch (Exception e) { log.warn("Orphaned old brand logo {}: {}", oldKey, e.getMessage()); }
            }
        }
        return new LogoUploadResponse(url);
    }

    public void deleteLogo(AuthPrincipal p) {
        Organization o = orgs.findById(p.orgId()).orElseThrow(() -> ApiException.notFound("Organization"));
        String url = o.getBrandLogoUrl();
        brandService.clearLogoUrl(p);
        if (url != null) {
            String key = storage.keyFor(url);
            if (key != null) {
                try { storage.delete(key); }
                catch (Exception e) { log.warn("Best-effort brand-logo delete failed for {}: {}", key, e.getMessage()); }
            }
        }
    }

    private static void validate(byte[] bytes, String contentType) {
        if (bytes.length > MAX_BYTES) throw fieldErr("must be ≤ 2 MB");
        if (!"image/png".equals(contentType)) throw fieldErr("PNG only for poster logos (SVG support later)");
        if (bytes.length < 8 || !isPngMagic(bytes)) throw fieldErr("content does not match declared type");

        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw fieldErr("could not decode PNG");
        }
        if (img == null) throw fieldErr("could not decode PNG");

        int w = img.getWidth();
        int h = img.getHeight();
        if (Math.min(w, h) < MIN_SHORT_SIDE) throw fieldErr("must be at least 128px on the short side");
        double aspect = (double) w / (double) h;
        if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) {
            throw fieldErr("aspect ratio must be between 1:4 and 4:1");
        }
    }

    private static boolean isPngMagic(byte[] b) {
        return (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50
                && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47;
    }

    private static ApiException fieldErr(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Invalid file", Map.of("file", msg));
    }

    private static String contentHash(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            byte[] prefix = new byte[8];
            System.arraycopy(digest, 0, prefix, 0, 8);
            return HexFormat.of().formatHex(prefix);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
