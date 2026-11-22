package com.javastorm.shop.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 把上传的商品图片保存到本地目录，并返回可访问的 URL（/uploads/products/xxx.jpg）。 */
@Service
public class ImageStorageService {

    public static final String URL_PREFIX = "/uploads/";
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private static final Logger log = LoggerFactory.getLogger(ImageStorageService.class);

    private final Path root;

    public ImageStorageService(@Value("${shop.upload-dir}") String uploadDir) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public Path getRoot() {
        return root;
    }

    public List<String> storeAll(List<MultipartFile> files) {
        List<String> urls = new ArrayList<>();
        if (files == null) {
            return urls;
        }
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                urls.add(store(file));
            }
        }
        return urls;
    }

    /** 删除本站上传目录里的图片文件；外部链接或不在上传目录内的路径会被忽略。 */
    public void deleteIfLocal(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return;
        }
        Path file = root.resolve(url.substring(URL_PREFIX.length())).normalize();
        if (!file.startsWith(root)) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除图片失败：{}", file, e);
        }
    }

    public String store(MultipartFile file) {
        String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
        ext = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException("不支持的图片格式：" + file.getOriginalFilename() + "（仅支持 jpg/png/gif/webp）");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.startsWith("image/")) {
            throw new BusinessException("文件不是图片：" + file.getOriginalFilename());
        }
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path dir = root.resolve("products");
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(dir);
            Files.copy(in, dir.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("图片保存失败：" + e.getMessage());
        }
        return URL_PREFIX + "products/" + filename;
    }
}
