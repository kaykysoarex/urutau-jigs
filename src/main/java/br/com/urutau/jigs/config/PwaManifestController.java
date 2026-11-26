package br.com.urutau.jigs.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PwaManifestController {
    @GetMapping("/manifest.webmanifest")
    ResponseEntity<Resource> manifest() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .contentType(MediaType.parseMediaType("application/manifest+json"))
            .body(new ClassPathResource("static/manifest.webmanifest"));
    }
}
