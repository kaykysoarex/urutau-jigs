package br.com.urutau.jigs.catalog;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "catalog_variant")
class CatalogVariant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private CatalogProduct product;

    @Column(name = "weight_grams", nullable = false)
    private int weight;

    @Column(name = "color_code", nullable = false, length = 16)
    private String color;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "retail_price", precision = 12, scale = 2)
    private BigDecimal retailPrice;

    @Column(name = "wholesale_price", precision = 12, scale = 2)
    private BigDecimal wholesalePrice;

    @Version
    private long version;

    protected CatalogVariant() {}

    CatalogVariant(CatalogProduct product, int weight, String color, boolean active) {
        this.product = product;
        this.weight = weight;
        this.color = color;
        this.active = active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    void setPrices(BigDecimal retailPrice, BigDecimal wholesalePrice) {
        this.retailPrice = retailPrice;
        this.wholesalePrice = wholesalePrice;
    }

    Long id() {
        return id;
    }

    CatalogProduct product() {
        return product;
    }

    int weight() {
        return weight;
    }

    String color() {
        return color;
    }

    boolean active() {
        return active;
    }

    BigDecimal retailPrice() {
        return retailPrice;
    }

    BigDecimal wholesalePrice() {
        return wholesalePrice;
    }
}
