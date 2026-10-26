package com.jkdc.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalLearnerIdentityTest {
    @Test fun signedOutIdentityIsStableGuestAndNeverOwnerless() {
        val guestID = "guest:install-9f2"

        assertEquals(guestID, LocalLearnerIdentity.resolve(null, guestID))
        assertEquals(guestID, LocalLearnerIdentity.resolve(null, guestID))
        assertNotEquals(guestID, LocalLearnerIdentity.resolve("member-a", guestID))
    }

    @Test fun switchingMembersChangesIdentityWithoutGuestFallback() {
        val guestID = "guest:install-9f2"

        assertEquals("member:member-a", LocalLearnerIdentity.resolve("member-a", guestID))
        assertEquals("member:member-b", LocalLearnerIdentity.resolve("member-b", guestID))
        assertEquals(guestID, LocalLearnerIdentity.resolve(null, guestID))
    }

    @Test fun progressSyncSelectsOnlyTheMemberSession() {
        val member = AccountSession("00000000-0000-0000-0000-00000000000a", "a".repeat(43), 100L)
        val guest = AccountSession("00000000-0000-0000-0000-00000000000b", "b".repeat(43), 100L)

        assertEquals(member, MemberProgressSyncPolicy.session(member, guest))
        assertEquals(null, MemberProgressSyncPolicy.session(null, guest))
    }
}
