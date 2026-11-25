package com.javastorm.shop.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 验证 S3 兼容对象存储后端的上传、CDN URL 生成、Cache-Control 头部与受管资源删除逻辑。 */
class S3ImageStorageTest {

    @Test
    void uploadsValidatedImageToS3AndDeletesManagedUrl() throws Exception {
        S3Client s3 = mock(S3Client.class);
        S3ImageStorageBackend backend = new S3ImageStorageBackend(
                s3, "my-shop-bucket", "https://cdn.example.com/", "storm-shop");
        ImageStorageService service = new ImageStorageService("./uploads", backend);

        byte[] png = tinyPng();
        MockMultipartFile file = new MockMultipartFile("files", "shoe.png", "image/png", png);

        String url = service.store(file);

        assertThat(url).startsWith("https://cdn.example.com/storm-shop/products/").endsWith(".png");
        assertThat(service.isManagedUrl(url)).isTrue();
        assertThat(service.isManagedUrl("https://other-site.com/a.png")).isFalse();

        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(putCaptor.capture(), any(RequestBody.class));
        assertThat(putCaptor.getValue().bucket()).isEqualTo("my-shop-bucket");
        assertThat(putCaptor.getValue().contentType()).isEqualTo("image/png");
        assertThat(putCaptor.getValue().cacheControl()).contains("immutable");

        // 外部链接不会被删
        service.deleteIfManaged("https://other-site.com/a.png");
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));

        // 受管 CDN 链接会正确提取 key 并删除
        service.deleteIfManaged(url);
        ArgumentCaptor<DeleteObjectRequest> delCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(delCaptor.capture());
        assertThat(delCaptor.getValue().bucket()).isEqualTo("my-shop-bucket");
        assertThat(delCaptor.getValue().key()).startsWith("storm-shop/products/").endsWith(".png");
    }

    private static byte[] tinyPng() throws Exception {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
