package br.com.urutau.jigs.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "commercial_settings")
class CommercialSettings {
    static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Column(name = "wholesale_threshold", nullable = false)
    private int wholesaleThreshold;

    @Version
    private long version;

    protected CommercialSettings() {}

    int wholesaleThreshold() {
        return wholesaleThreshold;
    }

    void setWholesaleThreshold(int wholesaleThreshold) {
        this.wholesaleThreshold = wholesaleThreshold;
    }
}
