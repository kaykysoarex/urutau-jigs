
package br.com.urutau.jigs.quotes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "commercial_customer")
class CommercialCustomer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Version
    long version;
    @Column(nullable = false, length = 120)
    String name;
    @Column(length = 120)
    String establishment;
    @Column(length = 40)
    String phone;
    @Column(length = 15)
    String normalizedPhone;
    @Column(length = 254)
    String email;
    @Column(length = 120)
    String city;
    @Column(length = 2)
    String state;
    @Column(length = 1000)
    String notes;
    @Column(nullable = false)
    boolean active = true;
    @Column(nullable = false)
    Instant createdAt;
    @Column(nullable = false)
    Instant updatedAt;

    protected CommercialCustomer() {}

    void fill(CustomerDtos.Input details, QuoteDtos.Client client, String normalized, Instant now) {
        name = client.name();
        establishment = CustomerService.optional(details.establishment());
        phone = client.phone();
        normalizedPhone = normalized;
        email = CustomerService.optional(details.email());
        city = client.city();
        state = client.state();
        notes = CustomerService.optional(details.notes());
        updatedAt = now;
    }

    CustomerDtos.View view() {
        return new CustomerDtos.View(id, version, name, establishment, phone, normalizedPhone, email,
                city, state, notes, active, createdAt, updatedAt);
    }

    CustomerDtos.Brief brief() {
        return new CustomerDtos.Brief(id, name, city, state, active, normalizedPhone);
    }

    CustomerDtos.Match match() {
        return new CustomerDtos.Match(id, version, name, establishment, phone, city, state, active);
    }
}
