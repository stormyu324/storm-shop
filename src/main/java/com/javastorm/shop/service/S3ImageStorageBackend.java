package com.javastorm.shop.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;

/**
 * S3 兼容对象存储实现（支持 AWS S3 / Cloudflare R2 / 阿里云 OSS / MinIO + CDN）。
 * 开启方式：{@code STORAGE_TYPE=s3} 并配置 {@code S3_BUCKET}、{@code S3_ACCESS_KEY}、
 * {@code S3_SECRET_KEY}、{@code S3_PUBLIC_BASE_URL}（可选 {@code S3_ENDPOINT}）。
 */
@Component
@ConditionalOnProperty(name = "shop.storage.type", havingValue = "s3")
public class S3ImageStorageBackend implements ImageStorageBackend {

    private static final Logger log = LoggerFactory.getLogger(S3ImageStorageBackend.class);
    private static final String CACHE_CONTROL_IMMUTABLE = "public, max-age=31536000, immutable";

    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;
    private final String keyPrefix;

    @Autowired
    public S3ImageStorageBackend(
            @Value("${shop.storage.s3.endpoint:}") String endpoint,
            @Value("${shop.storage.s3.region:auto}") String region,
            @Value("${shop.storage.s3.bucket}") String bucket,
            @Value("${shop.storage.s3.access-key}") String accessKey,
            @Value("${shop.storage.s3.secret-key}") String secretKey,
            @Value("${shop.storage.s3.public-base-url}") String publicBaseUrl,
            @Value("${shop.storage.s3.path-style-access:true}") boolean pathStyleAccess,
            @Value("${shop.storage.s3.key-prefix:storm-shop}") String keyPrefix) {
        this(buildClient(endpoint, region, accessKey, secretKey, pathStyleAccess), bucket, publicBaseUrl, keyPrefix);
    }

    S3ImageStorageBackend(S3Client s3, String bucket, String publicBaseUrl, String keyPrefix) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("启用 S3 存储时必须配置 S3_BUCKET");
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException("启用 S3 存储时必须配置 S3_PUBLIC_BASE_URL（如 CDN 域名）");
        }
        this.s3 = s3;
        this.bucket = bucket.trim();
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl.trim());
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    private static S3Client buildClient(String endpoint, String region, String accessKey, String secretKey,
                                        boolean pathStyleAccess) {
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException("启用 S3 存储时必须配置 S3_ACCESS_KEY 和 S3_SECRET_KEY");
        }
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region == null || region.isBlank() ? "auto" : region.trim()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey.trim(), secretKey.trim())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyleAccess)
                        .build());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.trim()));
        }
        return builder.build();
    }

    @Override
    public String put(String objectKey, byte[] bytes, String contentType) {
        String fullKey = fullKey(objectKey);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(fullKey)
                .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                .cacheControl(CACHE_CONTROL_IMMUTABLE)
                .build();
        try {
            s3.putObject(request, RequestBody.fromBytes(bytes));
        } catch (RuntimeException e) {
            throw new BusinessException("上传图片到对象存储失败：" + e.getMessage());
        }
        return publicBaseUrl + "/" + fullKey;
    }

    @Override
    public boolean isManagedUrl(String url) {
        if (url == null) {
            return false;
        }
        String managedPrefix = publicBaseUrl + "/" + (keyPrefix.isEmpty() ? "" : keyPrefix + "/");
        return url.startsWith(managedPrefix);
    }

    @Override
    public void delete(String url) {
        if (!isManagedUrl(url)) {
            return;
        }
        String key = url.substring(publicBaseUrl.length() + 1);
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException e) {
            log.warn("删除对象存储图片失败 bucket={} key={}", bucket, key, e);
        }
    }

    private String fullKey(String objectKey) {
        String cleaned = objectKey.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        return keyPrefix.isEmpty() ? cleaned : keyPrefix + "/" + cleaned;
    }

    private static String stripTrailingSlash(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String s = prefix.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
