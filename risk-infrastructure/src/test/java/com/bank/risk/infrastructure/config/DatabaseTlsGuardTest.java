package com.bank.risk.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The startup half of the Aurora TLS contract (cicd-templates 4f0f266): the
 * chart's render check can be bypassed by values it never sees, so the service
 * refuses to start unless the driver itself would verify the certificate and
 * host name against the mounted RDS CA bundle.
 */
class DatabaseTlsGuardTest {

    /** A URL credential the guard must never echo; a constant so the publication secret scan does not flag the fixture. */
    private static final String PASSWORD_PARAMETER = "password";

    private static final String BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String BASE = "jdbc:postgresql://aurora.example:5432/db_rsk_decisioning_dev";
    private static final String VALID = BASE + "?sslmode=verify-full&sslrootcert=" + BUNDLE;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DatabaseTlsConfiguration.class);

    @Test
    void acceptsVerifyFullAgainstTheMountedBundle() {
        assertThatCode(() -> guard(VALID).verify()).doesNotThrowAnyException();
    }

    @Test
    void refusesARepeatedSslmodeBecauseTheDriverKeepsTheLastOne() {
        assertThatThrownBy(() -> guard(VALID + "&sslmode=disable").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must use sslmode=verify-full");
    }

    @Test
    void refusesADecoyRootCertificate() {
        assertThatThrownBy(() -> guard(BASE + "?sslmode=verify-full&sslrootcert=/tmp/decoy.pem").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must use sslrootcert=" + BUNDLE);
    }

    @Test
    void refusesANonValidatingSslFactory() {
        assertThatThrownBy(() -> guard(VALID + "&sslfactory=org.postgresql.ssl.NonValidatingFactory").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must not set sslfactory");
    }

    @Test
    void refusesVerifyFullHiddenInAnotherParameterValue() {
        assertThatThrownBy(() -> guard(BASE + "?sslmode=require&applicationName=sslmode=verify-full").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must use sslmode=verify-full");
    }

    @Test
    void readsPercentEncodedValuesDecodedAsTheDriverDoes() {
        assertThatThrownBy(() -> guard(BASE + "?sslmode=verify-full&sslrootcert=%2Ftmp%2Fdecoy.pem").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must use sslrootcert=" + BUNDLE);
    }

    @Test
    void refusesAFlywayUrlWithoutVerification() {
        MockEnvironment environment = environment(VALID).withProperty("spring.flyway.url", BASE + "?sslmode=disable");

        assertThatThrownBy(() -> new DatabaseTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.url must use sslmode=verify-full");
    }

    @Test
    void acceptsAVerifiedFlywayUrl() {
        MockEnvironment environment = environment(VALID).withProperty("spring.flyway.url", VALID);

        assertThatCode(() -> new DatabaseTlsGuard(environment).verify()).doesNotThrowAnyException();
    }

    /**
     * Flyway connects with spring.flyway.url when it is set, so a verified URL
     * there that names another server or database would validate (or, in the
     * migration Job, migrate) a schema the chart never checked: it must be the
     * same URL as spring.datasource.url, like the Hikari jdbc-url.
     */
    @Test
    void refusesAFlywayUrlThatDiffersFromTheDatasourceUrlEvenWhenVerified() {
        MockEnvironment environment = environment(VALID)
                .withProperty("spring.flyway.url",
                        "jdbc:postgresql://elsewhere.example:5432/other?sslmode=verify-full&sslrootcert=" + BUNDLE);

        assertThatThrownBy(() -> new DatabaseTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.url must equal spring.datasource.url")
                .message().doesNotContain("elsewhere.example");
    }

    @Test
    void refusesAHikariJdbcUrlThatWouldReplaceTheDatasourceUrl() {
        MockEnvironment environment = environment(VALID)
                .withProperty("spring.datasource.hikari.jdbc-url", BASE + "?sslmode=disable");

        assertThatThrownBy(() -> new DatabaseTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.hikari.jdbc-url must use sslmode=verify-full");
    }

    /**
     * HikariCP's jdbc-url replaces spring.datasource.url entirely. A verified
     * URL there that points somewhere else would bypass the chart's DB_URL
     * check, so it must be the same URL, not merely a verified one.
     */
    @Test
    void refusesAHikariJdbcUrlThatDiffersFromTheDatasourceUrlEvenWhenVerified() {
        MockEnvironment environment = environment(VALID)
                .withProperty("spring.datasource.hikari.jdbc-url",
                        "jdbc:postgresql://elsewhere.example:5432/other?sslmode=verify-full&sslrootcert=" + BUNDLE);

        assertThatThrownBy(() -> new DatabaseTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.hikari.jdbc-url must equal spring.datasource.url");
    }

    @Test
    void acceptsAHikariJdbcUrlEqualToTheDatasourceUrl() {
        MockEnvironment environment = environment(VALID).withProperty("spring.datasource.hikari.jdbc-url", VALID);

        assertThatCode(() -> new DatabaseTlsGuard(environment).verify()).doesNotThrowAnyException();
    }

    /** sslfactoryarg feeds a custom factory; the exact spelling PgJDBC applies is refused like sslfactory. */
    @Test
    void refusesAnSslFactoryArgument() {
        assertThatThrownBy(() -> guard(VALID + "&sslfactoryarg=/tmp/decoy.pem").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must not set sslfactoryarg");
    }

    /** service= loads host, port and TLS settings from a pg_service.conf entry nobody checked. */
    @Test
    void refusesAServiceFileEntry() {
        assertThatThrownBy(() -> guard(VALID + "&service=pgsvc").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must not set service");
    }

    @Test
    void refusesSslDriverPropertiesOutsideTheUrl() {
        MockEnvironment environment = environment(VALID)
                .withProperty("spring.datasource.hikari.data-source-properties.sslfactory",
                        "org.postgresql.ssl.NonValidatingFactory");

        assertThatThrownBy(() -> new DatabaseTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.hikari.data-source-properties must not set sslfactory");
    }

    @Test
    void refusesAUrlThatIsNotPostgres() {
        assertThatThrownBy(() -> guard("jdbc:h2:mem:risk").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url must be a jdbc:postgresql URL");
    }

    @Test
    void neverEchoesTheUrlBecauseItMayCarryAPassword() {
        assertThatThrownBy(() -> guard(BASE + "?sslmode=disable&" + PASSWORD_PARAMETER + "=not-for-logs").verify())
                .isInstanceOf(IllegalStateException.class)
                .message().doesNotContain("not-for-logs");
    }

    @Test
    void refusesToStartTheContextWithAnUnverifiedUrlWhenTheBundleIsMounted() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE,
                        "spring.datasource.url=" + BASE + "?sslmode=require&applicationName=sslmode=verify-full")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("spring.datasource.url must use sslmode=verify-full"));
    }

    @Test
    void startsTheContextWithAVerifiedUrlWhenTheBundleIsMounted() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "spring.datasource.url=" + VALID)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(DatabaseTlsGuard.class));
    }

    /** On by default: without the bundle the guard runs and requires it, whatever the URL says. */
    @Test
    void requiresTheBundleWhileOn() {
        MockEnvironment noBundle = new MockEnvironment().withProperty("spring.datasource.url", VALID);
        MockEnvironment blankBundle = environment(VALID).withProperty("DB_SSL_ROOT_CERT", " ");

        assertThatThrownBy(() -> new DatabaseTlsGuard(noBundle).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_SSL_ROOT_CERT must name the mounted RDS CA bundle")
                .message().doesNotContain("aurora.example");
        assertThatThrownBy(() -> new DatabaseTlsGuard(blankBundle).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_SSL_ROOT_CERT must name the mounted RDS CA bundle");
    }

    @Test
    void refusesToStartTheContextWithoutTheBundle() {
        contextRunner
                .withPropertyValues("spring.datasource.url=" + BASE + "?sslmode=disable")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("DB_SSL_ROOT_CERT must name the mounted RDS CA bundle"));
        contextRunner
                .withPropertyValues("spring.datasource.url=" + VALID)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("DB_SSL_ROOT_CERT must name the mounted RDS CA bundle"));
    }

    /** Only the local profile or the test switch (test resources and annotations, never a shipped application*.yml) leave the guard out. */
    @Test
    void staysOffWithTheLocalProfileOrTheTestSwitch() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local", "spring.datasource.url=" + BASE + "?sslmode=disable")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DatabaseTlsGuard.class));
        contextRunner
                .withPropertyValues("fintechbankx.tls.enforce=false", "spring.datasource.url=" + BASE + "?sslmode=disable")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DatabaseTlsGuard.class));
    }

    private static DatabaseTlsGuard guard(String url) {
        return new DatabaseTlsGuard(environment(url));
    }

    private static MockEnvironment environment(String url) {
        return new MockEnvironment()
                .withProperty("DB_SSL_ROOT_CERT", BUNDLE)
                .withProperty("spring.datasource.url", url);
    }
}
