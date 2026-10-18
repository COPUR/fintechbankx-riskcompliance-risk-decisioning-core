package com.bank.risk.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Row of sc_rsk_decisioning.high_risk_country (V12). Written only by the
 * operator load script as the schema owner; the service reads it.
 */
@Entity
@Table(name = "high_risk_country")
public class HighRiskCountryJpaEntity {

    @Id
    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Column(name = "list_source", nullable = false, length = 128)
    private String listSource;

    @Column(name = "list_version", nullable = false, length = 64)
    private String listVersion;

    @Column(name = "loaded_at", nullable = false)
    private Instant loadedAt;

    protected HighRiskCountryJpaEntity() {
    }

    public String getCountryCode() { return countryCode; }
    public String getListSource() { return listSource; }
    public String getListVersion() { return listVersion; }
    public Instant getLoadedAt() { return loadedAt; }
}
