package com.javastorm.shop.api.admin;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/** CSV 文件下载响应。 */
final class Csv {

    private Csv() {
    }

    static ResponseEntity<byte[]> download(String filename, byte[] body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
