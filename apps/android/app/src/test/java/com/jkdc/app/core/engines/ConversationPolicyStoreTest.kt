package com.jkdc.app.core.engines

import com.jkdc.app.network.HelperPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationPolicyStoreTest {

    @Test
    fun `resolves the server model per purpose and defaults the null purpose`() {
        val store = ConversationPolicyStore()
        // No policy yet: refuse to guess.
        assertNull(store.modelFor(null))
        assertNull(store.current())
        store.update(ConversationPolicyState(mapOf("typed_reply" to "Strong", "meaning" to "Free"), 1500, 812))
        assertEquals("Strong", store.modelFor(null))
        assertEquals("Strong", store.modelFor(HelperPurpose.TYPED_REPLY))
        assertEquals("Free", store.modelFor(HelperPurpose.MEANING))
        // A purpose the policy does not cover is not a guessable case.
        assertNull(store.modelFor(HelperPurpose.TOPIC))
        assertEquals(688, store.current()!!.quotaRemaining)
    }

    @Test
    fun `blank model entries never win`() {
        val store = ConversationPolicyStore()
        store.update(ConversationPolicyState(mapOf("typed_reply" to "  "), 0, 0))
        assertNull(store.modelFor(null))
    }
}
