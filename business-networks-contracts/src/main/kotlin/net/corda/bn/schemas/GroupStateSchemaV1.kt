package net.corda.bn.schemas

import net.corda.bn.states.GroupState
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table
import jakarta.persistence.Column as JakartaColumn
import jakarta.persistence.Entity as JakartaEntity
import jakarta.persistence.Table as JakartaTable

/**
 * A database schema configured to represent [GroupState].
 */
object GroupStateSchemaV1 : MappedSchema(schemaFamily = GroupState::class.java, version = 1, mappedTypes = listOf(PersistentGroupState::class.java)) {

    /**
     * Mapped [GroupState] to be exported to a schema.
     *
     * The entity carries both the `javax.persistence` (Corda 4.14 and earlier, Hibernate 5) and the `jakarta.persistence` (Corda 4.15,
     * Hibernate 7) annotations, with identical attributes, so that one jar runs on both: Hibernate reads the set it understands and the
     * JVM drops the annotations whose classes are not on the node's classpath. Neither API jar is bundled with the CorDapp.
     *
     * @property networkId Mapped column for [GroupState.networkId].
     * @property name Mapped column for [GroupState.name].
     */
    @Entity
    @JakartaEntity
    @Table(name = "group_state")
    @JakartaTable(name = "group_state")
    class PersistentGroupState(
            @Column(name = "network_id")
            @JakartaColumn(name = "network_id")
            var networkId: String? = null,
            @Column(name = "name")
            @JakartaColumn(name = "name")
            var name: String? = null
    ) : PersistentState()
}
