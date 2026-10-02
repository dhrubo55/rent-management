package com.renteasebd.tenant;

import com.renteasebd.audit.dto.AuditTimelineItemResponse;
import com.renteasebd.common.ApiResponse;
import com.renteasebd.domain.tenant.TenantStatus;
import com.renteasebd.security.UserPrincipal;
import com.renteasebd.tenant.dto.CreateTenantRequest;
import com.renteasebd.tenant.dto.TenantDetailResponse;
import com.renteasebd.tenant.dto.TenantHistoryResponse;
import com.renteasebd.tenant.dto.TenantLedgerResponse;
import com.renteasebd.tenant.dto.TenantResponse;
import com.renteasebd.tenant.dto.UpdateTenantRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ApiResponse<List<TenantResponse>> list(
        @RequestParam(required = false) TenantStatus status,
        @RequestParam(required = false) Long propertyId,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(tenantService.list(status, propertyId, principal.id(), principal.role()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ApiResponse<TenantDetailResponse> get(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(tenantService.getDetail(id, principal.id(), principal.role()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ApiResponse<TenantResponse> create(
        @Valid @ModelAttribute CreateTenantRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(tenantService.create(request, principal.id(), principal.role()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ApiResponse<TenantResponse> update(
        @PathVariable Long id,
        @Valid @RequestBody UpdateTenantRequest request,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ApiResponse.ok(tenantService.update(id, request, principal.id(), principal.role()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ApiResponse<Void> deactivate(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        tenantService.deactivate(id, principal.id(), principal.role());
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ApiResponse<List<TenantHistoryResponse>> history(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(tenantService.history(id, principal.id(), principal.role()));
    }

    @GetMapping("/{id}/activity")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ApiResponse<List<AuditTimelineItemResponse>> activity(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(tenantService.activity(id, principal.id(), principal.role()));
    }

    @GetMapping("/{id}/ledger")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ApiResponse<TenantLedgerResponse> ledger(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.ok(tenantService.ledger(id, principal.id(), principal.role()));
    }

    @GetMapping("/{id}/nid-image")
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ResponseEntity<byte[]> getNidImage(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal principal) {
        byte[] body = tenantService.getNidImage(id, principal.id(), principal.role());
        String mimeType = tenantService.getNidImageMimeType(id, principal.id(), principal.role());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, mimeType)
            .body(body);
    }

    @PutMapping(path = "/{id}/nid-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OWNER','MANAGER')")
    public ApiResponse<Void> updateNidImage(
        @PathVariable Long id,
        @RequestParam("nidImage") MultipartFile nidImage,
        @AuthenticationPrincipal UserPrincipal principal
    ) {
        tenantService.updateNidImage(id, nidImage, principal.id(), principal.role());
        return ApiResponse.ok(null);
    }
}
