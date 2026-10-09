package com.orqix.hardening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.CannotCreateTransactionException;

import com.orqix.shared.problem.ProblemWriter;
import com.orqix.shared.web.DatabaseUnavailableFilter;
import com.orqix.support.IntegrationTestBase;
import com.orqix.support.TestDatabase;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * T-RES-04 (in-JVM part): how the HTTP layer answers while the database is unavailable. The Docker scenario
 * {@code tools/resilience/resilience.py dboutage} showed that a request carrying a session cookie never reached the
 * controllers: the JDBC session lookup in the servlet filters failed, the container answered with an HTML {@code 500}
 * page and repeated the lookup in the error dispatch (90 s). S5 section 12 requires {@code 503 DEPENDENCY_UNAVAILABLE}.
 */
class DatabaseOutageResponseTest extends IntegrationTestBase {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProblemWriter problems;

    @Test
    void sessionLookupFailureAnswers503ProblemJsonFromTheFilterChain() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        var config = hikari.getHikariConfigMXBean();
        try {
            // New connections are refused (wrong password): the pool behaves like a database that cannot be reached,
            // without stopping the shared test container. The request waits one connection timeout (30 s).
            config.setPassword("wrong-password");
            hikari.getHikariPoolMXBean().softEvictConnections();

            String sessionId = Base64.getEncoder().encodeToString(UUID.randomUUID().toString()
                    .getBytes(StandardCharsets.UTF_8));
            long started = System.nanoTime();
            MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/me")
                    .cookie(new Cookie("ORQIX_SESSION", sessionId))).andReturn();
            long millis = (System.nanoTime() - started) / 1_000_000;

            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(503);
            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
            assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("5");
            assertThat(json.parse(result.getResponse().getContentAsString()).path("code").asString())
                    .isEqualTo("DEPENDENCY_UNAVAILABLE");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("Exception", "SQL", "password");
            assertThat(millis).as("one bounded connection wait (30 s), not repeated").isLessThan(45_000);
        } finally {
            config.setPassword(TestDatabase.APP_PASSWORD);
            hikari.getHikariPoolMXBean().softEvictConnections();
            Awaitility.await().atMost(Duration.ofSeconds(30)).ignoreExceptions()
                    .until(() -> jdbc.sql("SELECT 1").query(Integer.class).single() == 1);
        }
    }

    @Test
    void filterTranslatesOnlyDatabaseAvailabilityFailuresAndRunsBeforeTheSessionRepository() throws Exception {
        DatabaseUnavailableFilter filter = new DatabaseUnavailableFilter(new ProblemWriter(JsonMapper.builder().build()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new CannotCreateTransactionException("Could not open JDBC Connection for transaction",
                        new SQLTransientConnectionException("orqix - Connection is not available"));
            }
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("DEPENDENCY_UNAVAILABLE");

        // Everything else is not hidden: it still reaches the container as an error.
        MockHttpServletResponse other = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, other, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws ServletException {
                throw new IllegalStateException("a defect");
            }
        })).isInstanceOf(IllegalStateException.class);

        int order = DatabaseUnavailableFilter.class.getAnnotation(Order.class).value();
        assertThat(order).as("ahead of the session repository filter").isLessThan(SessionRepositoryFilter.DEFAULT_ORDER);
    }
}
