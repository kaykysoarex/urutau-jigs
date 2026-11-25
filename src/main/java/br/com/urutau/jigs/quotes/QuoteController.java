package br.com.urutau.jigs.quotes;

import br.com.urutau.jigs.pdf.QuotePdf;
import br.com.urutau.jigs.quotes.QuoteDtos.History;
import br.com.urutau.jigs.quotes.QuoteDtos.IssueRequest;
import br.com.urutau.jigs.quotes.QuoteDtos.PdfPreviewRequest;
import br.com.urutau.jigs.quotes.QuoteDtos.Preview;
import br.com.urutau.jigs.quotes.QuoteDtos.PreviewRequest;
import br.com.urutau.jigs.quotes.QuoteDtos.ShareDetails;
import br.com.urutau.jigs.quotes.QuoteDtos.Summary;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quotes")
class QuoteController {
    private final QuoteService quotes;
    private final QuotePdf pdf;

    QuoteController(QuoteService quotes, QuotePdf pdf) {
        this.quotes = quotes;
        this.pdf = pdf;
    }

    @PostMapping("/preview")
    Preview preview(@Valid @RequestBody PreviewRequest request) {
        return quotes.preview(request.items());
    }

    @PostMapping("/preview/pdf")
    ResponseEntity<byte[]> previewPdf(@Valid @RequestBody PdfPreviewRequest request,
                                      @RequestParam(defaultValue = "false") boolean download) {
        return pdfResponse(pdf.renderPreview(quotes.pdfPreview(request)), "previa-orcamento-urutau.pdf", download);
    }

    @PostMapping
    ResponseEntity<Summary> issue(@Valid @RequestBody IssueRequest request, Principal principal) {
        String admin = principal.getName();
        Summary summary;
        try {
            summary = quotes.issue(request, admin);
        } catch (DataIntegrityViolationException concurrentSubmission) {
            summary = quotes.findIssued(admin, request.requestId()).orElseThrow(() -> concurrentSubmission);
        }
        return ResponseEntity.created(URI.create("/api/quotes/" + summary.number() + "/pdf")).body(summary);
    }

    @GetMapping
    History history(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return quotes.history(page, size);
    }

    @GetMapping("/{number:\\d+}")
    ResponseEntity<ShareDetails> shareDetails(@PathVariable long number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(quotes.shareDetails(number));
    }

    @GetMapping("/{number}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable long number, @RequestParam(defaultValue = "false") boolean download) {
        var filename = String.format(Locale.ROOT, "orcamento-urutau-%05d.pdf", number);
        return pdfResponse(pdf.render(quotes.document(number)), filename, download);
    }

    private static ResponseEntity<byte[]> pdfResponse(byte[] content, String filename, boolean download) {
        var disposition = download ? ContentDisposition.attachment() : ContentDisposition.inline();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(filename).build().toString())
                .body(content);
    }
}
