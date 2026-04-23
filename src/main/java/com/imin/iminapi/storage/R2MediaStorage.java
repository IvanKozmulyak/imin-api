package com.imin.iminapi.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
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
}
