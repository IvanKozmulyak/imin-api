package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.MediaUploadResponse;
import com.imin.iminapi.model.MediaKind;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.MediaUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}/media")
public class EventMediaController {

    private final MediaUploadService uploadService;

    public EventMediaController(MediaUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(path = "/{kind}", consumes = "multipart/form-data")
    public MediaUploadResponse upload(@CurrentUser AuthPrincipal p,
                                      @PathVariable UUID eventId,
                                      @PathVariable String kind,
                                      @RequestPart("file") MultipartFile file) throws IOException {
        MediaKind k = MediaKind.fromWire(kind);
        return uploadService.upload(p, eventId, k, file.getBytes(),
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename());
    }

    @DeleteMapping("/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser AuthPrincipal p,
                       @PathVariable UUID eventId,
                       @PathVariable String kind) {
        uploadService.delete(p, eventId, MediaKind.fromWire(kind));
    }
}
