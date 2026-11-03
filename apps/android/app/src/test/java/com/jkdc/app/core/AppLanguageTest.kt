package com.jkdc.app.core

import org.junit.Assert.assertEquals
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
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(listOf("fr")))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTags(listOf("zh-Hans")))
    }

    @Test fun optionIdsMapToStableTags() {
        assertEquals(null, AppLanguage.tag(AppLanguage.SYSTEM))
        assertEquals("en", AppLanguage.tag(AppLanguage.ENGLISH))
        assertEquals("es", AppLanguage.tag(AppLanguage.SPANISH))
        // Unknown ids must fall back to "follow the system", never to a wrong locale.
        assertEquals(null, AppLanguage.tag("klingon"))
    }

    @Test fun selectorOptionsAreTheThreeSupportedChoices() {
        assertEquals(listOf("system", "en", "es"), AppLanguage.OPTIONS)
    }
}
