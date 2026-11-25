package br.com.urutau.jigs.quotes;

import br.com.urutau.jigs.pdf.QuotePdf;
import br.com.urutau.jigs.quotes.QuoteDtos.Line;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "commercial_quote_item")
class QuoteItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id")
    private CommercialQuote quote;
    @Column(nullable = false)
    private long variantId;
    @Column(nullable = false, length = 120)
    private String modelName;
    @Column(nullable = false)
    private int weightGrams;
    @Column(nullable = false, length = 16)
    private String colorCode;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false, precision = 24, scale = 2)
    private BigDecimal unitPrice;
    @Column(nullable = false, precision = 24, scale = 2)
    private BigDecimal subtotal;

    protected QuoteItem() {}

    QuoteItem(CommercialQuote quote, Line line) {
        this.quote = quote;
        this.variantId = line.variantId();
        this.modelName = line.model();
        this.weightGrams = line.weight();
        this.colorCode = line.color();
        this.quantity = line.quantity();
        this.unitPrice = line.unitPrice();
        this.subtotal = line.subtotal();
    }

    QuotePdf.Line documentLine() {
        return new QuotePdf.Line(modelName, weightGrams, colorCode, quantity, unitPrice, subtotal);
    }
}
