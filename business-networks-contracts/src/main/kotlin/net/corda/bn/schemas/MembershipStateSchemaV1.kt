package net.corda.bn.schemas

import net.corda.bn.states.BNIdentity
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.serialization.CordaSerializable
import java.time.Instant
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * A database schema configured to represent [MembershipState].
 */
@CordaSerializable
object MembershipStateSchemaV1 : MappedSchema(schemaFamily = MembershipState::class.java, version = 1, mappedTypes = listOf(PersistentMembershipState::class.java)) {

    /**
     * Mapped [MembershipState] to be exported to a schema.
     *
     * @property cordaIdentity Mapped column for Corda part of [MembershipState.identity].
     * @property networkId Mapped column for [MembershipState.networkId].
     * @property status Mapped column for [MembershipState.status].
     */
    @Entity
    @Table(name = "membership_state")
    class PersistentMembershipState(
            @Column(name = "corda_identity", nullable = false)
            val cordaIdentity: Party,
            /** String representation of custom [BNIdentity] */
            @Column(name = "business_identity", nullable = false)
            val businessIdentity: String,
            @Column(name = "network_id", nullable = false)
            val networkId: String,
            @Column(name = "status", nullable = false)
            val status: MembershipStatus,
            @Column(name = "issuer_identity", nullable = false)
            val issuer: Party,
            @Column(name = "issued", nullable = false)
            val issued: Instant,
            @Column(name = "modified", nullable = false)
            val modified: Instant
    ) : PersistentState()
}