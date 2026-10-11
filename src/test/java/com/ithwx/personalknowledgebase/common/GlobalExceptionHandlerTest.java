package com.ithwx.personalknowledgebase.common;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldReturnBadRequest() {
        ResponseEntity<ApiErrorResponse> response = handler.handleBadRequest(
                new IllegalArgumentException("问题不能为空"),
                request("/api/chat/stream")
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("问题不能为空", response.getBody().message());
    }

    @Test
    void shouldReturnNotFound() {
        ResponseEntity<ApiErrorResponse> response = handler.handleNotFound(
                new NoSuchElementException("资料不存在：99"),
                request("/api/documents/99")
        );

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("NOT_FOUND", response.getBody().code());
    }

    @Test
    void shouldReportFileSizeLimitInChinese() {
        ResponseEntity<ApiErrorResponse> response = handler.handleFileTooLarge(
                new MaxUploadSizeExceededException(20 * 1024 * 1024),
                request("/api/documents")
        );

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("FILE_TOO_LARGE", response.getBody().code());
        assertEquals("文件超过上传大小限制，请缩小文件后重试", response.getBody().message());
    }

    @Test
    void shouldHideInternalErrorMessage() {
        ResponseEntity<ApiErrorResponse> response = handler.handleUnexpected(
                new RuntimeException("数据库密码等内部信息"),
                request("/api/documents")
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("服务器内部错误", response.getBody().message());
        assertNotNull(response.getBody().timestamp());
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(path);
        return request;
    }
}
