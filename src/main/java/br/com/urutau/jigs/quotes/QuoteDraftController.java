
package br.com.urutau.jigs.quotes;

import br.com.urutau.jigs.quotes.QuoteDraftDtos.Listed;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Opened;
import br.com.urutau.jigs.quotes.QuoteDraftDtos.Save;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quote-drafts")
class QuoteDraftController {
    private final QuoteDraftService drafts;

    QuoteDraftController(QuoteDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping
    ResponseEntity<List<Listed>> list(Principal principal) {
        return privateResponse(drafts.list(principal.getName()));
    }

    @GetMapping("/{id}")
    ResponseEntity<Opened> open(@PathVariable UUID id, Principal principal) {
        return privateResponse(drafts.open(id, principal.getName()));
    }

    @PutMapping("/{id}")
    ResponseEntity<Listed> save(@PathVariable UUID id, @Valid @RequestBody Save request, Principal principal) {
        String owner = principal.getName();
        try {
            return privateResponse(drafts.save(id, owner, request));
        } catch (DataIntegrityViolationException duplicate) {
            // Two creation requests can see the ID as absent before the first insert commits.
            return privateResponse(drafts.retryCreation(id, owner, request));
        }
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> discard(@PathVariable UUID id, @RequestParam @PositiveOrZero long version, Principal principal) {
        drafts.discard(id, principal.getName(), version);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
