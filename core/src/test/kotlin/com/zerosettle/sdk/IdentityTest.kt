package com.zerosettle.sdk

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IdentityTest {
    @Test fun user_storesIdNameEmail() {
        val id = Identity.User(id = "u1", name = "Alice", email = "a@example.com")
        assertThat(id.id).isEqualTo("u1")
        assertThat(id.name).isEqualTo("Alice")
        assertThat(id.email).isEqualTo("a@example.com")
    }

    @Test fun user_defaultsNameEmailToNull() {
        val id = Identity.User(id = "u1")
        assertThat(id.name).isNull()
        assertThat(id.email).isNull()
    }

    @Test fun user_emptyId_throws() {
        try {
            Identity.User(id = "")
            error("expected throw")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("id")
        }
    }

    @Test fun anonymousAndDeferred_areDataObjects() {
        assertThat(Identity.Anonymous).isSameInstanceAs(Identity.Anonymous)
        assertThat(Identity.Deferred).isSameInstanceAs(Identity.Deferred)
        assertThat(Identity.Anonymous as Identity).isNotEqualTo(Identity.Deferred as Identity)
    }

    @Test fun whenExpression_coversAllVariants() {
        val identities: List<Identity> = listOf(
            Identity.User("u"), Identity.Anonymous, Identity.Deferred,
        )
        val labels = identities.map {
            when (it) {
                is Identity.User -> "user:${it.id}"
                Identity.Anonymous -> "anonymous"
                Identity.Deferred -> "deferred"
            }
        }
        assertThat(labels).containsExactly("user:u", "anonymous", "deferred").inOrder()
    }
}
