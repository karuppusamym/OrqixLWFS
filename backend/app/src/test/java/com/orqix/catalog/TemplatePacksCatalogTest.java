package com.orqix.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.orqix.catalog.internal.TemplateCatalog;
import com.orqix.shared.json.Json;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Regional template packs are packaged from examples/pack-*.json and offered with pack, industry and region. */
class TemplatePacksCatalogTest {

    private final TemplateCatalog catalog = new TemplateCatalog(new Json(JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()));

    @Test
    void generalTemplatesComeFirstAndCarryNoPack() {
        TemplateCatalog.Template first = catalog.all().getFirst();
        assertThat(first.id()).isEqualTo("purchase-approval");
        assertThat(first.pack()).isNull();
        assertThat(first.region()).isNull();
        assertThat(first.languages()).isEmpty();
    }

    @Test
    void everyRegionalPackTemplateIsOfferedWithItsPackRegionAndOwner() {
        List<TemplateCatalog.Template> packs = catalog.all().stream().filter(t -> t.pack() != null).toList();
        assertThat(packs).hasSizeGreaterThanOrEqualTo(20);
        assertThat(packs).extracting(TemplateCatalog.Template::region)
                .contains("India", "European Union", "Kenya", "Nigeria");
        assertThat(packs).extracting(TemplateCatalog.Template::industry)
                .contains("Banking and microfinance", "Shared services", "Public sector");
        TemplateCatalog.Template loan = catalog.find("pack-in-loan-exception").orElseThrow();
        assertThat(loan.region()).isEqualTo("India");
        assertThat(loan.pack()).isEqualTo("banking-mfi");
        assertThat(loan.languages()).containsExactly("hi");
        assertThat(loan.typicalOwner()).isEqualTo("Credit operations");
        assertThat(loan.requiredGroups()).contains("branch-managers", "credit-committee");
        assertThat(loan.requiredConnections()).isEmpty();
        assertThat(loan.requiredWorkflows()).isEmpty();
        assertThat(catalog.find("pack-ng-kyc-refresh").orElseThrow().languages()).isEmpty();
    }
}
