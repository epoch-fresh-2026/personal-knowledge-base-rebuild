package com.ithwx.personalknowledgebase.controller;

import com.ithwx.personalknowledgebase.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = DocumentUploadLimitTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@Timeout(45)
class DocumentUploadLimitTest {

    @Value("${local.server.port}") private int port;

    @Test
    void shouldAcceptTheReportedPptSize() throws Exception {
        int fileSize = 9_294 * 1_024;
        HttpResponse<String> response = upload(fileSize);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"size\":" + fileSize));
    }

    @Test
    void shouldRejectFilesAbove20MbWithReadableChineseResponse() throws Exception {
        // 同时检查刚超限，以及剩余上传数据超过 Tomcat 默认 2MB 丢弃上限的情况。
        for (int fileSize : new int[]{20 * 1_024 * 1_024 + 1, 23 * 1_024 * 1_024}) {
            HttpResponse<String> response = upload(fileSize);
            assertEquals(413, response.statusCode(), "fileSize=" + fileSize);
            assertTrue(response.body().contains("FILE_TOO_LARGE"));
            assertTrue(response.body().contains("文件超过上传大小限制"));
        }
    }

    private HttpResponse<String> upload(int fileSize) throws Exception {
        String boundary = "upload-limit-test";
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"test.ppt\"\r\n"
                + "Content-Type: application/vnd.ms-powerpoint\r\n\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/upload-test"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(header.getBytes(StandardCharsets.UTF_8)),
                        HttpRequest.BodyPublishers.ofByteArray(new byte[fileSize]),
                        HttpRequest.BodyPublishers.ofByteArray(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8))))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // 只启动上传所需的 Web 组件，复用 application.yaml；不连接数据库或模型。
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            MultipartAutoConfiguration.class, JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class})
    @Import({UploadEndpoint.class, GlobalExceptionHandler.class})
    static class TestApplication {}

    @RestController
    static class UploadEndpoint {
        @PostMapping("/upload-test")
        Map<String, Long> upload(@RequestParam("file") MultipartFile file) {
            return Map.of("size", file.getSize());
        }
    }
}
