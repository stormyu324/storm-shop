package com.javastorm.shop.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class ImageValidationTest {
    @TempDir Path directory;
    @Test
    void rejectsForgedImageBeforeWriting() throws Exception {
        var storage = new ImageStorageService(directory.toString());
        var file = new MockMultipartFile("files", "fake.png", "image/png", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> storage.store(file)).isInstanceOf(BusinessException.class);
        try (var files = java.nio.file.Files.list(directory)) {
            assertThat(files.count()).isZero();
        }
    }
}
