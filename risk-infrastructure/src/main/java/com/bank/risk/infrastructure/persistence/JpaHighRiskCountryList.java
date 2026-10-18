package com.bank.risk.infrastructure.persistence;

import com.bank.risk.domain.HighRiskCountries;
import com.bank.risk.domain.port.out.HighRiskCountryList;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Out-port adapter for {@link HighRiskCountryList} over sc_rsk_decisioning.high_risk_country.
 * The load script writes one loaded_at per load; should rows from different
 * loads ever coexist, the oldest loaded_at is the list's age (fail closed) and
 * every distinct source and version is named.
 */
@Repository
@Transactional(readOnly = true)
public class JpaHighRiskCountryList implements HighRiskCountryList {

    private final SpringDataHighRiskCountryRepository countries;

    public JpaHighRiskCountryList(SpringDataHighRiskCountryRepository countries) {
        this.countries = countries;
    }

    @Override
    public Optional<HighRiskCountries> current() {
        List<HighRiskCountryJpaEntity> rows = countries.findAll();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Set<String> codes = rows.stream().map(HighRiskCountryJpaEntity::getCountryCode).collect(Collectors.toSet());
        Instant oldestLoad = rows.stream().map(HighRiskCountryJpaEntity::getLoadedAt).min(Comparator.naturalOrder()).orElseThrow();
        String source = String.join(",", rows.stream().map(HighRiskCountryJpaEntity::getListSource).collect(Collectors.toCollection(TreeSet::new)));
        String version = String.join(",", rows.stream().map(HighRiskCountryJpaEntity::getListVersion).collect(Collectors.toCollection(TreeSet::new)));
        return Optional.of(new HighRiskCountries(codes, source, version, oldestLoad));
    }
}
