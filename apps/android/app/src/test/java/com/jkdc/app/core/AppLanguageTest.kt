package com.jkdc.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    @Test fun defaultsToSystemWhenNoTagsAreStored() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(emptyList()))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(listOf("")))
    }

    @Test fun mapsKnownLanguageTagsToOptions() {
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags(listOf("en")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTags(listOf("en-GB")))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTags(listOf("es")))
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromTags(listOf("es-MX")))
    }

    @Test fun mapsUnknownTagsToSystem() {
        // Languages the app does not ship UI resources for.
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(listOf("ko")))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(listOf("ar")))
    }

    @Test fun regionalVariantsResolveToTheirBaseLanguage() {
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTags(listOf("zh-Hans")))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.fromTags(listOf("pt-BR")))
    }

    @Test fun optionIdsMapToStableTags() {
        assertEquals(null, AppLanguage.tag(AppLanguage.SYSTEM))
        assertEquals("en", AppLanguage.tag(AppLanguage.ENGLISH))
        assertEquals("es", AppLanguage.tag(AppLanguage.SPANISH))
        // Unknown ids must fall back to "follow the system", never to a wrong locale.
        assertEquals(null, AppLanguage.tag("klingon"))
    }

    @Test fun selectorOptionsAreTheNineSupportedChoices() {
        assertEquals(listOf("system", "en", "es", "fr", "de", "it", "pt", "nb", "zh"), AppLanguage.OPTIONS)
    }

    @Test fun everyConcreteOptionHasANativeLabelAndAStableTag() {
        for (id in AppLanguage.OPTIONS.filter { it != AppLanguage.SYSTEM }) {
            assertEquals(id, AppLanguage.tag(id))
            assertTrue(AppLanguage.NATIVE_LABELS.containsKey(id))
        }
    }
}
