package com.imin.iminapi.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@ConditionalOnProperty(name = "imin.media.enabled", havingValue = "true")
public class R2MediaStorage implements MediaStorage {

    private final S3Client s3;
    private final String bucket;
    private final String publicPrefix;

    public R2MediaStorage(S3Client s3,
                          @Value("${imin.media.bucket}") String bucket,
                          @Value("${imin.media.public-url-prefix}") String publicPrefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.publicPrefix = publicPrefix.endsWith("/") ? publicPrefix : publicPrefix + "/";
    }

    @Override
    public Stored put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(key)
                        .contentType(contentType)
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(bytes));
        return new Stored(publicPrefix + key, bytes.length, contentType);
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public byte[] read(String key, long maxBytes) {
        try (var in = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
            if (bytes.length > maxBytes) throw new IllegalStateException("Object larger than " + maxBytes + " bytes");
            return bytes;
        } catch (NoSuchKeyException missing) {
            return null;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to read " + key, e);
        }
    }

    @Override
    public String urlFor(String key) { return publicPrefix + key; }

    @Override
    public String keyFor(String url) {
        if (url == null || !url.startsWith(publicPrefix)) return null;
        return url.substring(publicPrefix.length());
    }
}
