package com.imin.iminapi.controller.org;

import com.imin.iminapi.dto.org.BrandBookDto;
import com.imin.iminapi.dto.org.BrandUpdateRequest;
import com.imin.iminapi.dto.org.LogoUploadResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.org.OrgBrandService;
import com.imin.iminapi.service.org.OrgMediaService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/org/brand")
public class OrgBrandController {

    private final OrgBrandService brandService;
    private final OrgMediaService mediaService;

    public OrgBrandController(OrgBrandService brandService, OrgMediaService mediaService) {
        this.brandService = brandService;
        this.mediaService = mediaService;
    }

    @GetMapping
    public BrandBookDto get(@CurrentUser AuthPrincipal p) {
        return brandService.get(p);
    }

    @PutMapping
    public BrandBookDto put(@CurrentUser AuthPrincipal p, @RequestBody BrandUpdateRequest body) {
        return brandService.put(p, body);
    }

    @PostMapping(path = "/logo", consumes = "multipart/form-data")
    public LogoUploadResponse uploadLogo(@CurrentUser AuthPrincipal p,
                                         @RequestPart("file") MultipartFile file) throws IOException {
        return mediaService.uploadLogo(p, file.getBytes(),
                file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                file.getOriginalFilename() == null ? "logo.png" : file.getOriginalFilename());
    }

    @DeleteMapping("/logo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLogo(@CurrentUser AuthPrincipal p) {
        mediaService.deleteLogo(p);
    }
}
