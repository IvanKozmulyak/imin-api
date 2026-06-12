package com.imin.iminapi.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerUploadSizeTest {

    GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void max_upload_size_maps_to_413_with_envelope() {
        ResponseEntity<ApiError> resp =
                handler.handleMaxUpload(new MaxUploadSizeExceededException(2L * 1024 * 1024));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().error().code()).isEqualTo(ErrorCode.FIELD_INVALID.name());
        assertThat(resp.getBody().error().fields()).containsKey("file");
    }
}
