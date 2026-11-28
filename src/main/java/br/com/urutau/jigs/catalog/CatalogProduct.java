package br.com.urutau.jigs.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "catalog_product")
class CatalogProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "name_key", nullable = false, length = 240)
    private String nameKey;

    @Column(nullable = false)
    private boolean active;

    java.time.Instant createdAt;
    java.time.Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "product", cascade = CascadeType.PERSIST)
    @OrderBy("weight ASC, color ASC")
    private List<CatalogVariant> variants = new ArrayList<>();

    protected CatalogProduct() {}

    CatalogProduct(String name, boolean active) {
        update(name, active);
    }

    void update(String newName, boolean newActive) {
        name = newName.strip();
        nameKey = nameKey(name);
        active = newActive;
    }

    static String nameKey(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    void addVariant(int weight, String color, boolean variantActive) {
        variants.add(new CatalogVariant(this, weight, color, variantActive));
    }

    long version() { return version; }

    Long id() {
        return id;
    }

    String name() {
        return name;
    }

    boolean active() {
        return active;
    }

    List<CatalogVariant> variants() {
        return variants;
    }
}
