package br.com.urutau.jigs.quotes;

import br.com.urutau.jigs.pdf.QuotePdf;
import br.com.urutau.jigs.quotes.QuoteDtos.Client;
import br.com.urutau.jigs.quotes.QuoteDtos.Preview;
import br.com.urutau.jigs.quotes.QuoteDtos.PriceTier;
import br.com.urutau.jigs.quotes.QuoteDtos.Status;
import br.com.urutau.jigs.quotes.QuoteDtos.Summary;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "commercial_quote")
class CommercialQuote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 36)
    private String requestId;
    @Column(nullable = false, length = 80)
    private String createdBy;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private LocalDate issuedOn;
    @Column(nullable = false, length = 120)
    private String clientName;
    @Column(length = 40)
    private String clientPhone;
    @Column(length = 120)
    private String clientCity;
    @Column(length = 2)
    private String clientState;
    @Column(nullable = false)
    private int totalQuantity;
    @Column(nullable = false, precision = 24, scale = 2)
    private BigDecimal total;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PriceTier priceTier;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status;
    @OneToMany(mappedBy = "quote", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<QuoteItem> items = new ArrayList<>();

    protected CommercialQuote() {}

    CommercialQuote(String requestId, String createdBy, Instant createdAt, LocalDate issuedOn, Client client, Preview preview) {
        this.requestId = requestId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.issuedOn = issuedOn;
        this.clientName = client.name();
        this.clientPhone = client.phone();
        this.clientCity = client.city();
        this.clientState = client.state();
        this.totalQuantity = preview.quantity();
        this.total = preview.total();
        this.priceTier = preview.priceTier();
        this.status = Status.PENDENTE;
        preview.items().forEach(line -> items.add(new QuoteItem(this, line)));
    }

    Summary summary() {
        return new Summary(id, clientName, createdAt, totalQuantity, total, status);
    }

    QuotePdf.Document document() {
        var client = new QuotePdf.Client(clientName, clientPhone, clientCity, clientState);
        return new QuotePdf.Document(id, issuedOn, client, items.stream().map(QuoteItem::documentLine).toList(),
                totalQuantity, total);
    }
}
