package net.corda.bn.schemas

import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.serialization.CordaSerializable
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table
import jakarta.persistence.Column as JakartaColumn
import jakarta.persistence.Entity as JakartaEntity
import jakarta.persistence.Table as JakartaTable

/**
 * A database schema configured to represent [MembershipState].
 */
@CordaSerializable
object MembershipStateSchemaV1 : MappedSchema(schemaFamily = MembershipState::class.java, version = 1, mappedTypes = listOf(PersistentMembershipState::class.java)) {

    /**
     * Mapped [MembershipState] to be exported to a schema.
     *
     * The entity carries both the `javax.persistence` (Corda 4.14 and earlier, Hibernate 5) and the `jakarta.persistence` (Corda 4.15,
     * Hibernate 7) annotations, with identical attributes, so that one jar runs on both: Hibernate reads the set it understands and the
     * JVM drops the annotations whose classes are not on the node's classpath. Neither API jar is bundled with the CorDapp.
     *
     * @property cordaIdentity Mapped column for Corda part of [MembershipState.identity].
     * @property networkId Mapped column for [MembershipState.networkId].
     * @property status Mapped column for [MembershipState.status].
     */
    @Entity
    @JakartaEntity
    @Table(name = "membership_state")
    @JakartaTable(name = "membership_state")
    class PersistentMembershipState() : PersistentState() {
        constructor(
                cordaIdentity: Party,
                networkId: String,
                status: MembershipStatus
        ) : this() {
            this.cordaIdentity = cordaIdentity
            this.networkId = networkId
            this.status = status
        }
        @Column(name = "corda_identity")
        @JakartaColumn(name = "corda_identity")
        lateinit var cordaIdentity: Party
        @Column(name = "network_id")
        @JakartaColumn(name = "network_id")
        lateinit var networkId: String
        @Column(name = "status")
        @JakartaColumn(name = "status")
        lateinit var status: MembershipStatus
    }
}
