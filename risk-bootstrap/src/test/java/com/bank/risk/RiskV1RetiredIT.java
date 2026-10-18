package com.bank.risk;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The other value of RISK_V1_CALLER_FACTS_ENABLED (S5): once the operator turns it off after payments is on
 * v2, POST /api/v1/risk/assess is 410 API_VERSION_GONE naming v2, while reads of stored decisions stay.
 * RiskServiceIT runs with the default (true), so a deploy before payments moves breaks nothing.
 */
@SpringBootTest(properties = {"risk.outbox.relay.enabled=false", "fintechbankx.tls.enforce=false",
    "risk.v1.caller-facts-enabled=false", "risk.country.home=AE"})
@AutoConfigureMockMvc
class RiskV1RetiredIT {

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void v1AssessIsGoneWhileReadsStay() throws Exception {
        mvc.perform(post("/api/v1/risk/assess")
                .with(jwt().jwt(j -> j.subject("service-account-payments").claim("azp", "svc-pay-initiation-settlement"))
                    .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"transactionId": "PAY-V1-GONE", "amount": 10.00, "currency": "USD", "highRiskCountry": false, "velocityScore": 0, "paymentType": "TRANSFER"}
                    """))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.code").value("API_VERSION_GONE"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("/api/v2/risk/assess")));
        mvc.perform(get("/api/v1/risk/assessments/{id}", "PAY-V1-GONE")
                .with(jwt().jwt(j -> j.subject("service-account-payments").claim("azp", "svc-pay-initiation-settlement"))
                    .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
            .andExpect(status().isNotFound());
    }
}
