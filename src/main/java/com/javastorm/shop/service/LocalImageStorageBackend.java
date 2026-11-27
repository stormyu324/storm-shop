package com.javastorm.shop.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 本地磁盘存储实现（默认，适合本地开发和单机部署）。 */
@Component
@ConditionalOnProperty(name = "shop.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalImageStorageBackend implements ImageStorageBackend {

    private static final Logger log = LoggerFactory.getLogger(LocalImageStorageBackend.class);

    private final Path root;

    public LocalImageStorageBackend(@Value("${shop.upload-dir}") String uploadDir) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @Override
    public String put(String objectKey, byte[] bytes, String contentType) {
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("非法的图片存储路径");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new BusinessException("图片保存失败：" + e.getMessage());
        }
        return ImageStorageService.URL_PREFIX + objectKey.replace('\\', '/');
    }

    @Override
    public boolean isManagedUrl(String url) {
        return url != null && url.startsWith(ImageStorageService.URL_PREFIX);
    }

    @Override
    public void delete(String url) {
        if (!isManagedUrl(url)) {
            return;
        }
        Path file = root.resolve(url.substring(ImageStorageService.URL_PREFIX.length())).normalize();
        if (!file.startsWith(root)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除本地图片失败：{}", file, e);
        }
    }
}
