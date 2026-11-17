package net.corda.bn.schemas

import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.serialization.CordaSerializable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.TypeDef
import org.hibernate.type.StringType
import java.sql.Types
import javax.persistence.AttributeOverride
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table
import jakarta.persistence.Column as JakartaColumn
import jakarta.persistence.Entity as JakartaEntity
import jakarta.persistence.Table as JakartaTable
import org.hibernate.annotations.Target as HibernateTarget

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
     * The changelog creates the string columns as `VARCHAR`; see [GroupStateSchemaV1.PersistentGroupState] for why and how each
     * Hibernate version maps them as `VARCHAR` under Corda Enterprise's nationalised character data. `corda_identity` is stored through
     * the node's `AbstractParty` converter (the X.500 name), whose Hibernate 5 mapping a CorDapp cannot change either, so, like
     * `transaction_id`, its `javax.persistence` column declares the `VARCHAR2(255)` the changelog creates for the validation; on
     * Hibernate 7 `@JdbcTypeCode(Types.VARCHAR)` applies to the converted value.
     *
     * @property cordaIdentity Mapped column for Corda part of [MembershipState.identity].
     * @property networkId Mapped column for [MembershipState.networkId].
     * @property status Mapped column for [MembershipState.status].
     */
    @Entity
    @JakartaEntity
    @Table(name = "membership_state")
    @JakartaTable(name = "membership_state")
    @AttributeOverride(name = "stateRef.txId", column = Column(name = "transaction_id", nullable = false, columnDefinition = "VARCHAR2(64)"))
    @TypeDef(typeClass = StringType::class, defaultForType = PersistentMembershipState.VarcharString::class)
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
        @Column(name = "corda_identity", columnDefinition = "VARCHAR2(255)")
        @JakartaColumn(name = "corda_identity")
        @JdbcTypeCode(Types.VARCHAR)
        lateinit var cordaIdentity: Party
        @Column(name = "network_id")
        @JakartaColumn(name = "network_id")
        @HibernateTarget(VarcharString::class)
        @JdbcTypeCode(Types.VARCHAR)
        lateinit var networkId: String
        @Column(name = "status")
        @JakartaColumn(name = "status")
        lateinit var status: MembershipStatus

        /** Never instantiated: only the key of the Hibernate 5 type definition that maps a string attribute as `VARCHAR` (see [GroupStateSchemaV1.PersistentGroupState]). */
        class VarcharString private constructor()
    }
}
