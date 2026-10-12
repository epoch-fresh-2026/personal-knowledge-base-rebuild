package com.ithwx.personalknowledgebase.library.infrastructure;

import com.ithwx.personalknowledgebase.library.application.FileStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

@Component
public class LocalFileStorage implements FileStorage {

    private final Path storageDirectory;

    public LocalFileStorage(
            @Value("${app.storage.directory:data/documents}") String storageDirectory
    ) {
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    @Override
    public String save(String filename, byte[] content) throws IOException {
        Files.createDirectories(storageDirectory);
        // 文件格式已由上传入口检查；保留扩展名，使用随机名称存储。
        String storedName = UUID.randomUUID() + filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        Files.write(storageDirectory.resolve(storedName), content);
        return storedName;
    }

    @Override
    public byte[] read(String storedPath) throws IOException {
        Path path = storageDirectory.resolve(storedPath).normalize();
        if (!path.startsWith(storageDirectory)) {
            throw new IllegalArgumentException("文件路径不合法");
        }
        return Files.readAllBytes(path);
    }

}
