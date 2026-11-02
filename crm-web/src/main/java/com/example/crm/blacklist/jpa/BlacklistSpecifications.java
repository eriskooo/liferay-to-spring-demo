package com.example.crm.blacklist.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.example.crm.blacklist.BlacklistSearchCriteria;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/** Kritériá vyhľadávania ako JPA Specification – legacy DAO skladalo WHERE ručne podľa vyplnených polí. */
public final class BlacklistSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private BlacklistSpecifications() {
    }

    /** Jméno/priezvisko ako podreťazec bez ohľadu na veľkosť písmen, identifikátor presne, stav presne. */
    public static Specification<BlacklistRecordEntity> matching(BlacklistSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (hasText(criteria.getFirstName())) {
                predicates.add(cb.like(cb.lower(root.<String>get("firstName")),
                        containsPattern(criteria.getFirstName()), LIKE_ESCAPE));
            }
            if (hasText(criteria.getLastName())) {
                predicates.add(cb.like(cb.lower(root.<String>get("lastName")),
                        containsPattern(criteria.getLastName()), LIKE_ESCAPE));
            }
            if (hasText(criteria.getIdentifierValue())) {
                Subquery<Long> identifier = query.subquery(Long.class);
                Root<IdentifierEntity> i = identifier.from(IdentifierEntity.class);
                identifier.select(i.<Long>get("id")).where(
                        cb.equal(i.get("record"), root),
                        cb.equal(cb.lower(i.<String>get("value")),
                                criteria.getIdentifierValue().trim().toLowerCase(Locale.ROOT)));
                predicates.add(cb.exists(identifier));
            }
            if (criteria.getState() != null) {
                predicates.add(cb.equal(root.get("state"), criteria.getState()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Používateľ hľadá text, nie LIKE vzor – % a _ sa escapujú. */
    private static String containsPattern(String query) {
        String lower = query.trim().toLowerCase(Locale.ROOT);
        return "%" + lower.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
