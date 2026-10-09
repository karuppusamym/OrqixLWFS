package com.orqix.identity;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.orqix.shared.Ids;
import com.orqix.shared.web.Sql;

/** Subjects: unique by (issuer, external subject); email is display data only (S2 section 2). */
@Service
public class SubjectService {

    public record Subject(UUID id, String issuer, String externalSubject, String displayName, String email,
            boolean disabled) {
    }

    /** API {@code SubjectRef}. */
    public record SubjectRef(UUID id, String displayName) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public SubjectService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Creates or refreshes the subject; {@code login=true} also sets {@code last_login_at}. */
    @Transactional(propagation = Propagation.REQUIRED)
    public Subject upsert(String issuer, String externalSubject, String displayName, String email, boolean login) {
        String name = displayName == null || displayName.isBlank() ? externalSubject : displayName;
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        return jdbc.sql("""
                INSERT INTO subject (id, issuer, external_subject, display_name, email, first_seen_at, last_login_at)
                VALUES (:id, :issuer, :sub, :name, :email, :now, :login)
                ON CONFLICT (issuer, external_subject) DO UPDATE
                   SET display_name = EXCLUDED.display_name, email = EXCLUDED.email,
                       last_login_at = COALESCE(EXCLUDED.last_login_at, subject.last_login_at)
                RETURNING id, issuer, external_subject, display_name, email, disabled_at
                """)
                .param("id", Ids.newId()).param("issuer", issuer).param("sub", externalSubject).param("name", name)
                .param("email", email).param("now", Sql.ts(clock.instant()))
                .param("login", login ? Sql.ts(clock.instant()) : null)
                .query((rs, n) -> new Subject(Sql.uuid(rs, "id"), rs.getString("issuer"), rs.getString("external_subject"),
                        rs.getString("display_name"), rs.getString("email"), rs.getObject("disabled_at") != null))
                .single();
    }

    /** Stores the IdP groups of the latest login (V4), used to evaluate eligibility of a non-caller subject. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordGroups(UUID subjectId, List<String> groups, String groupsJson) {
        jdbc.sql("UPDATE subject SET idp_groups = CAST(:groups AS jsonb) WHERE id = :id")
                .param("groups", groupsJson).param("id", subjectId).update();
    }

    @Transactional(readOnly = true)
    public List<String> storedGroups(UUID subjectId) {
        return jdbc.sql("SELECT jsonb_array_elements_text(idp_groups) FROM subject WHERE id = :id")
                .param("id", subjectId).query(String.class).list();
    }

    @Transactional(readOnly = true)
    public Optional<Subject> find(String issuer, String externalSubject) {
        return jdbc.sql("""
                SELECT id, issuer, external_subject, display_name, email, disabled_at FROM subject
                WHERE issuer = :issuer AND external_subject = :sub
                """)
                .param("issuer", issuer).param("sub", externalSubject)
                .query((rs, n) -> new Subject(Sql.uuid(rs, "id"), rs.getString("issuer"), rs.getString("external_subject"),
                        rs.getString("display_name"), rs.getString("email"), rs.getObject("disabled_at") != null))
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<Subject> find(UUID id) {
        return jdbc.sql("SELECT id, issuer, external_subject, display_name, email, disabled_at FROM subject WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new Subject(Sql.uuid(rs, "id"), rs.getString("issuer"), rs.getString("external_subject"),
                        rs.getString("display_name"), rs.getString("email"), rs.getObject("disabled_at") != null))
                .optional();
    }

    /** Display names by subject id (unknown ids map to "Unknown user"). */
    @Transactional(readOnly = true)
    public Map<UUID, String> displayNames(Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        List<UUID> wanted = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, display_name FROM subject WHERE id IN (:ids)").param("ids", wanted)
                .query((rs, n) -> names.put(Sql.uuid(rs, "id"), rs.getString("display_name"))).list();
        return names;
    }

    public SubjectRef ref(UUID id, Map<UUID, String> names) {
        if (id == null) {
            return null;
        }
        return new SubjectRef(id, names.getOrDefault(id, "Unknown user"));
    }

    @Transactional(readOnly = true)
    public SubjectRef ref(UUID id) {
        if (id == null) {
            return null;
        }
        return ref(id, displayNames(List.of(id)));
    }
}
