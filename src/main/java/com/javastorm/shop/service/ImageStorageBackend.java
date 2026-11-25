package com.javastorm.shop.service;

/**
 * 商品图片底层存储抽象：支持本地目录（开发/单机）与 S3 兼容对象存储（AWS S3 / Cloudflare R2 / 阿里云 OSS / MinIO）。
 */
public interface ImageStorageBackend {

    /** 上传对象并返回前端/CDN 可访问的完整 URL 或站内相对路径。 */
    String put(String objectKey, byte[] bytes, String contentType);

    /** 判断 URL 是否属于本存储后端管理（外部粘贴的第三方图片链接返回 false，不会被清理）。 */
    boolean isManagedUrl(String url);

    /** 删除受管对象；非受管 URL 或不存在时静默忽略。 */
    void delete(String url);
}
