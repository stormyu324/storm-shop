package com.javastorm.shop.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

/**
 * 商品图片校验与存储服务：统一执行扩展名、MIME、大小与像素解码校验，
 * 底层通过 {@link ImageStorageBackend} 支持本地目录和 S3/OSS/R2 对象存储无缝切换。
 */
@Service
public class ImageStorageService {

    public static final String URL_PREFIX = "/uploads/";
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final Path root;
    private final ImageStorageBackend backend;

    @Autowired
    public ImageStorageService(@Value("${shop.upload-dir}") String uploadDir, ImageStorageBackend backend) {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
        this.backend = backend;
    }

    public ImageStorageService(String uploadDir) {
        this(uploadDir, new LocalImageStorageBackend(uploadDir));
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

    public boolean isManagedUrl(String url) {
        return backend.isManagedUrl(url);
    }

    /** 删除受管图片（本地上传目录或对象存储）；外部粘贴的图片链接会被忽略。 */
    public void deleteIfManaged(String url) {
        backend.delete(url);
    }

    /** 兼容旧调用名称。 */
    public void deleteIfLocal(String url) {
        deleteIfManaged(url);
    }

    private void validateImage(byte[] bytes) {
        if (bytes.length > 10L * 1024 * 1024) {
            throw new BusinessException("图片超过 10MB 限制");
        }
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             ImageInputStream image = ImageIO.createImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(image);
            if (!readers.hasNext()) {
                throw new BusinessException("图片内容无效或格式不受支持");
            }
            var reader = readers.next();
            try {
                reader.setInput(image);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 20_000_000L) {
                    throw new BusinessException("图片尺寸超过 2000 万像素限制");
                }
                if (reader.read(0) == null) {
                    throw new BusinessException("图片内容无效");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new BusinessException("图片内容无效或已损坏");
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
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new BusinessException("图片超过 10MB 限制");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("读取图片失败：" + e.getMessage());
        }
        validateImage(bytes);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        return backend.put("products/" + filename, bytes, contentType);
    }
}
