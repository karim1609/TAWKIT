package com.tawkit.service.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the bundled recordings referenced by the settings picker. */
class AdhanCatalogTest {

    @Test
    void everyRecordingIsBundled() {
        for (AdhanCatalog.Entry entry : AdhanCatalog.recordings()) {
            assertNotNull(AdhanCatalogTest.class.getResource(entry.resource()),
                    "missing bundled file for " + entry.id() + ": " + entry.resource());
        }
    }

    @Test
    void catalogContainsTheFullCollection() {
        assertTrue(AdhanCatalog.recordings().size() >= 200,
                "expected the full bundled collection, got "
                        + AdhanCatalog.recordings().size());
    }

    @Test
    void defaultEntryIsBundled() {
        assertNotNull(AdhanCatalogTest.class.getResource(AdhanCatalog.BUNDLED_DEFAULT.resource()),
                "missing default adhan");
    }

    @Test
    void choicesStartWithDefaultAndResolveIds() {
        assertEquals(AdhanCatalog.BUNDLED_DEFAULT, AdhanCatalog.choices().get(0));
        assertEquals("", AdhanCatalog.byId(null).id());
        assertEquals("", AdhanCatalog.byId("").id());
        assertEquals("", AdhanCatalog.byId("no-such-recording").id());
        assertEquals("turkey_1", AdhanCatalog.byId("turkey_1").id());
    }

    @Test
    void idsAreUniqueAndNamesAreFilled() {
        var ids = AdhanCatalog.recordings().stream().map(AdhanCatalog.Entry::id).toList();
        assertEquals(ids.size(), ids.stream().distinct().count());
        assertTrue(AdhanCatalog.recordings().stream()
                .allMatch(e -> !e.displayName().isBlank()));
    }
}
