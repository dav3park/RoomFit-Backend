package com.roomfit.consultation;

import com.roomfit.common.CommonResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

@RestController
@RequestMapping("/api/design-consultation")
public class DesignConsultationController {
    private final DesignConsultationService service;
    public DesignConsultationController(DesignConsultationService service) { this.service = service; }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommonResponse<DesignConsultationResult> analyze(
            @Valid @RequestPart("brief") DesignConsultationRequest request,
            @RequestPart(value = "images", required = false) List<MultipartFile> images) {
        return CommonResponse.ok(service.analyze(request, images == null ? List.of() : images));
    }
}
