package com.fluxbank.beneficiary.api;

import com.fluxbank.beneficiary.application.BeneficiaryCreationResult;
import com.fluxbank.beneficiary.application.BeneficiaryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/beneficiaries")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    public BeneficiaryController(
            BeneficiaryService beneficiaryService) {
        this.beneficiaryService = beneficiaryService;
    }

    @PostMapping
    public ResponseEntity<BeneficiaryResponse> create(
            Authentication authentication,
            @Valid @RequestBody CreateBeneficiaryRequest request) {

        BeneficiaryCreationResult result =
                beneficiaryService.create(
                        authentication.getName(),
                        request
                );

        if (result.created()) {
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(result.beneficiary());
        }

        return ResponseEntity.ok(result.beneficiary());
    }
    @GetMapping
    public ResponseEntity<List<BeneficiaryResponse>> getAll(
            Authentication authentication) {

        return ResponseEntity.ok(
                beneficiaryService.getAll(
                        authentication.getName()
                )
        );
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<BeneficiaryResponse> getByPublicId(
            Authentication authentication,
            @PathVariable UUID publicId) {

        return ResponseEntity.ok(
                beneficiaryService.getByPublicId(
                        authentication.getName(),
                        publicId
                )
        );
    }
    @DeleteMapping("/{publicId}")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable UUID publicId) {

        beneficiaryService.delete(
                authentication.getName(),
                publicId
        );

        return ResponseEntity.noContent().build();
    }
}