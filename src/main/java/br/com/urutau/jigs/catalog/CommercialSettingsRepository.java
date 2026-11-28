package br.com.urutau.jigs.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

interface CommercialSettingsRepository extends JpaRepository<CommercialSettings, Integer> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from CommercialSettings s where s.id = 1")
    CommercialSettings lockSettings();
}
