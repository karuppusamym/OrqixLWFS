package com.orqix.migration.camunda;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.orqix.migration.camunda.Expressions.Comparison;
import com.orqix.migration.camunda.Expressions.Logical;
import com.orqix.migration.camunda.Expressions.Op;
import com.orqix.migration.camunda.Expressions.Unsupported;

/** The operators the canonical schema and compiler accept: eq ne gte gt lt lte, flat all/any groups, never anything else. */
class ExpressionsTest {

    private static Comparison one(String text) {
        var parsed = Expressions.parse(text, null, null);
        assertThat(parsed).as(text).isInstanceOf(Comparison.class);
        return (Comparison) parsed;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "${amount >= 5000}|amount|GTE|5000", "${amount > 5000}|amount|GT|5000", "${amount < 5000}|amount|LT|5000",
            "${amount <= 5000}|amount|LTE|5000", "${amount == 5000}|amount|EQ|5000", "${amount != 5000}|amount|NE|5000",
            "${amount ge 5000}|amount|GTE|5000", "${amount gt 5000}|amount|GT|5000", "${amount le 5000}|amount|LTE|5000",
            "${amount ne 5000}|amount|NE|5000", "${amount eq 5000.50}|amount|EQ|5000.50", "${amount > -1}|amount|GT|-1",
            "= amount >= 5000|amount|GTE|5000", "= amount > 5000|amount|GT|5000", "= amount <= 5000|amount|LTE|5000",
            "= amount != 5000|amount|NE|5000", "= amount = 5000|amount|EQ|5000", "= amount < 5000|amount|LT|5000"})
    void everySupportedOperatorInJuelAndFeel(String text, String variable, Op op, String literal) {
        Comparison c = one(text);
        assertThat(c.variable()).isEqualTo(variable);
        assertThat(c.op()).isEqualTo(op);
        assertThat(((BigDecimal) c.literal()).compareTo(new BigDecimal(literal))).isZero();
    }

    @Test
    void textAndYesNoComparisonsAllowOnlyEqualityAndInequality() {
        assertThat(one("${status != 'new'}").op()).isEqualTo(Op.NE);
        assertThat(one("= status = \"open\"").literal()).isEqualTo("open");
        assertThat(one("${active == true}").literal()).isEqualTo(true);
        assertThat(one("${active != false}").op()).isEqualTo(Op.NE);
        for (String ordering : List.of("${status > 'a'}", "= status <= \"b\"", "${active >= true}")) {
            assertThat(Expressions.parse(ordering, null, null)).as(ordering).isInstanceOf(Unsupported.class);
        }
    }

    @Test
    void bareAndNegatedYesNoVariablesKeepTheirMeaning() {
        assertThat(one("${approved}")).extracting(Comparison::literal).isEqualTo(true);
        assertThat(one("${!approved}")).extracting(Comparison::literal).isEqualTo(false);
        assertThat(one("= not(approved)")).extracting(Comparison::literal).isEqualTo(false);
        assertThat(one("${not approved}").booleanShortcut()).isTrue();
    }

    @Test
    void notOverOneComparisonInvertsTheOperator() {
        assertThat(one("= not(amount >= 5000)")).extracting(Comparison::op).isEqualTo(Op.LT);
        assertThat(one("= not(amount > 5000)")).extracting(Comparison::op).isEqualTo(Op.LTE);
        assertThat(one("= not(amount < 5)")).extracting(Comparison::op).isEqualTo(Op.GTE);
        assertThat(one("= not(amount <= 5)")).extracting(Comparison::op).isEqualTo(Op.GT);
        assertThat(one("= not(status = \"x\")")).extracting(Comparison::op).isEqualTo(Op.NE);
        assertThat(one("${not (status != 'x')}")).extracting(Comparison::op).isEqualTo(Op.EQ);
        assertThat(one("${!(active == true)}")).extracting(Comparison::literal).isEqualTo(false);
        assertThat(one("= not(approved = false)")).extracting(Comparison::literal).isEqualTo(true);
        Logical group = (Logical) Expressions.parse("= not(a > 1) and b < 2", null, null);
        assertThat(group.terms()).extracting(Comparison::op).containsExactly(Op.LTE, Op.LT);
        assertThat(one("= not(approved)").booleanShortcut()).isTrue();
    }

    @Test
    void flatGroupsBecomeAllOrAnyWithTheOriginalTermsInOrder() {
        for (String and : List.of("${amount > 1000 && amount <= 10000}", "${amount > 1000 and amount <= 10000}",
                "= amount > 1000 and amount <= 10000")) {
            var parsed = Expressions.parse(and, null, null);
            assertThat(parsed).as(and).isInstanceOf(Logical.class);
            Logical l = (Logical) parsed;
            assertThat(l.group()).isEqualTo("all");
            assertThat(l.terms()).extracting(Comparison::op).containsExactly(Op.GT, Op.LTE);
        }
        for (String or : List.of("${region == 'EU' || region == 'UK'}", "${region == 'EU' or region == 'UK'}",
                "= region = \"EU\" or region = \"UK\"")) {
            Logical l = (Logical) Expressions.parse(or, null, null);
            assertThat(l.group()).as(or).isEqualTo("any");
            assertThat(l.terms()).extracting(Comparison::literal).containsExactly("EU", "UK");
        }
        Logical mixedFields = (Logical) Expressions.parse("${risk == 'high' || score ge 80}", null, null);
        assertThat(mixedFields.terms()).extracting(Comparison::variable).containsExactly("risk", "score");
        assertThat(mixedFields.describe()).isEqualTo("risk == 'high' or score >= 80");
        Logical parenthesised = (Logical) Expressions.parse("${(amount > 1) and (approved)}", null, null);
        assertThat(parenthesised.terms()).hasSize(2);
    }

    @Test
    void aGroupHoldsAtMostEightTerms() {
        String eight = String.join(" and ", java.util.Collections.nCopies(8, "x > 1"));
        assertThat(Expressions.parse("${" + eight + "}", null, null)).isInstanceOf(Logical.class);
        var nine = Expressions.parse("${" + eight + " and x > 1}", null, null);
        assertThat(nine).isInstanceOf(Unsupported.class);
        assertThat(((Unsupported) nine).reason()).contains("at most 8");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "${a > 1 && b < 2 || c == 3}|mixes 'and' with 'or'",
            "= status in (\"a\", b)|list after 'in'",
            "= status in (\"a\",)|list after 'in'",
            "= status in ()|list after 'in'",
            "= status in (>5, 6)|list after 'in'",
            "= status in (\"a\", \"b\") and other = 1|nests a group",
            "= x between 1 and \"z\"|not supported",
            "= x between a and 5|not supported",
            "= x in [5..1]|ends below",
            "= x in [\"a\"..\"b\"]|not supported",
            "= x in [1..2] or y = 1 and z = 2|mixes",
            "= x between 1 and 2 or y between 3 and 4|nests a group",
            "= (a > 1 or b > 1) and c > 1|nests a group",
            "= date(x) > 1|function call",
            "= 5 < 6|not a simple",
            "= x between 1 and 2 + 3|not supported",
            "${x between 1 and 2}|not supported",
            "${x in (1, 2)}|not supported",
            "${(a > 1 && b < 2) || c == 3}|nests a group",
            "${customer.age > 18}|property access",
            "${fn(x) > 1}|function call",
            "= contains(name, \"x\")|contains",
            "= owner = null|null",
            "= owner != null|null",
            "${owner == null}|null",
            "= if a > 1 then true else false|FEEL construct",
            "= items[1] > 1|list, range or context",
            "= not(a.b > 1)|property access",
            "${amount * 2 > 100}|arithmetic",
            "${amount > 1 ? 2 : 3}|arithmetic",
            "${amount > limit}|simple comparison",
            "${amount >}|simple comparison",
            "${a > 1 &&}|simple comparison",
            "${a > 1 && 7}|simple comparison",
            "amount > 1|not a ${...}",
            "${}|"})
    void everythingElseIsUnsupportedWithAReason(String text, String reasonPart) {
        var parsed = Expressions.parse(text, null, null);
        assertThat(parsed).as(text).isInstanceOf(Unsupported.class);
        if (reasonPart != null && !reasonPart.isEmpty()) {
            assertThat(((Unsupported) parsed).reason()).as(text).contains(reasonPart);
        }
    }

    @Test
    void literalOnTheLeftIsTurnedAround() {
        assertThat(one("${5000 <= amount}")).extracting(Comparison::variable, Comparison::op).containsExactly("amount", Op.GTE);
        assertThat(one("= 5000 > amount")).extracting(Comparison::op).isEqualTo(Op.LT);
        assertThat(one("= \"new\" = status")).extracting(Comparison::op, Comparison::literal).containsExactly(Op.EQ, "new");
        assertThat(one("= 5 < amount")).extracting(Comparison::op).isEqualTo(Op.GT);
        assertThat(Expressions.parse("${\"a\" < status}", null, null)).isInstanceOf(Unsupported.class);
    }

    @Test
    void betweenAndRangesBecomeAnAllGroupWithTheRightBounds() {
        Logical between = (Logical) Expressions.parse("= amount between 1000 and 5000", null, null);
        assertThat(between.group()).isEqualTo("all");
        assertThat(between.terms()).extracting(Comparison::op).containsExactly(Op.GTE, Op.LTE);
        Logical halfOpen = (Logical) Expressions.parse("= amount in [1000..5000)", null, null);
        assertThat(halfOpen.terms()).extracting(Comparison::op).containsExactly(Op.GTE, Op.LT);
        Logical open = (Logical) Expressions.parse("= amount in (1.5..5)", null, null);
        assertThat(open.terms()).extracting(Comparison::op).containsExactly(Op.GT, Op.LT);
        assertThat(open.describe()).isEqualTo("amount > 1.5 and amount < 5");
    }

    @Test
    void betweenJoinsAGroupWithoutStealingItsAnd() {
        Logical group = (Logical) Expressions.parse("= amount between 1 and 5 and status = \"open\"", null, null);
        assertThat(group.group()).isEqualTo("all");
        assertThat(group.terms()).extracting(Comparison::describe).containsExactly("amount >= 1", "amount <= 5", "status == 'open'");
    }

    @Test
    void inListBecomesAnAnyGroupOfEqualities() {
        Logical list = (Logical) Expressions.parse("= region in (\"EU\", \"UK\", \"CH\")", null, null);
        assertThat(list.group()).isEqualTo("any");
        assertThat(list.terms()).extracting(Comparison::literal).containsExactly("EU", "UK", "CH");
        assertThat(Expressions.parse("= level in (1, 2)", null, null)).isInstanceOf(Logical.class);
        assertThat(Expressions.parse("= region in (\"EU\")", null, null)).isInstanceOf(Comparison.class);
        Logical orGroup = (Logical) Expressions.parse("= region in (\"EU\", \"UK\") or risk = \"high\"", null, null);
        assertThat(orGroup.terms()).hasSize(3);
        assertThat(((Unsupported) Expressions.parse("= region in (\"a;b\", \"c\")", null, null)).reason()).contains("characters");
        assertThat(((Unsupported) Expressions.parse("= x in (1, \"a\")", null, null)).reason()).contains("different types");
        String nine = String.join(", ", java.util.Collections.nCopies(9, "1"));
        assertThat(((Unsupported) Expressions.parse("= x in (" + nine + ")", null, null)).reason()).contains("at most 8");
    }

    @Test
    void notOverAGroupIsInvertedTermByTerm() {
        Logical a = (Logical) Expressions.parse("= not(a > 1 and b < 2)", null, null);
        assertThat(a.group()).isEqualTo("any");
        assertThat(a.terms()).extracting(Comparison::op).containsExactly(Op.LTE, Op.GTE);
        Logical b = (Logical) Expressions.parse("${!(a > 1 || status == 'x')}", null, null);
        assertThat(b.group()).isEqualTo("all");
        assertThat(b.terms()).extracting(Comparison::op).containsExactly(Op.LTE, Op.NE);
        Logical c = (Logical) Expressions.parse("= not(x between 1 and 5)", null, null);
        assertThat(c.group()).isEqualTo("any");
        assertThat(c.terms()).extracting(Comparison::op).containsExactly(Op.LT, Op.GT);
        Logical d = (Logical) Expressions.parse("= not(region in (\"EU\", \"UK\"))", null, null);
        assertThat(d.group()).isEqualTo("all");
        assertThat(d.terms()).extracting(Comparison::op).containsExactly(Op.NE, Op.NE);
    }

    @Test
    void sameConnectiveGroupsInsideParenthesesAreFlattened() {
        Logical g = (Logical) Expressions.parse("${(a > 1 && b < 2) && (c == 3 && d != 4)}", null, null);
        assertThat(g.group()).isEqualTo("all");
        assertThat(g.terms()).extracting(Comparison::variable).containsExactly("a", "b", "c", "d");
        Logical h = (Logical) Expressions.parse("= (a > 1 or b < 2) or c = 3", null, null);
        assertThat(h.group()).isEqualTo("any");
        assertThat(h.terms()).hasSize(3);
        String five = String.join(" and ", java.util.Collections.nCopies(5, "x > 1"));
        var tooMany = Expressions.parse("= (" + five + ") and (" + five + ")", null, null);
        assertThat(((Unsupported) tooMany).reason()).contains("at most 8");
    }

    @Test
    void unsafeTextAndMixedTypesAreExplainedNotConverted() {
        assertThat(((Unsupported) Expressions.parse("${status == 'a;b'}", null, null)).reason()).contains("characters");
        assertThat(((Unsupported) Expressions.parse("${a == 1 && a == 'x'}", null, null)).reason()).contains("different types");
    }

    @ParameterizedTest
    @ValueSource(strings = {"groovy", "javascript", "python"})
    void scriptLanguagesAndResourcesAreNeverRead(String language) {
        assertThat(Expressions.parse("${amount > 1}", language, null)).isInstanceOf(Unsupported.class);
        assertThat(Expressions.parse("${amount > 1}", "juel", "script.groovy")).isInstanceOf(Unsupported.class);
        assertThat(Expressions.parse("${amount > 1}", "juel", null)).isInstanceOf(Comparison.class);
    }

    @Test
    void feelSymbolConnectivesAreNotAcceptedAndJuelEqualsIsNot() {
        assertThat(Expressions.parse("= a > 1 && b < 2", null, null)).isInstanceOf(Unsupported.class);
        assertThat(Expressions.parse("${a = 1}", null, null)).isInstanceOf(Unsupported.class);
    }
}
