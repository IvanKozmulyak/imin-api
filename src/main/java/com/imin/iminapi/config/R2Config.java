package com.imin.iminapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
@ConditionalOnProperty(name = "imin.media.enabled", havingValue = "true")
public class R2Config {

    @Bean
    public S3Client s3Client(@Value("${imin.media.endpoint}") String endpoint,
                             @Value("${imin.media.region}") String region,
                             @Value("${imin.media.access-key-id}") String accessKeyId,
                             @Value("${imin.media.secret-access-key}") String secret) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKeyId, secret)))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }
}
