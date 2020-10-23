package net.corda.bn.schemas

import net.corda.bn.states.ChangeRequestState
import net.corda.bn.states.ChangeRequestStatus
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * A database schema configured to represent [ChangeRequestState].
 */
object ChangeRequestStateSchemaV1 : MappedSchema(schemaFamily = ChangeRequestState::class.java, version = 1, mappedTypes = listOf(PersistentChangeRequestState::class.java)) {

    /**
     * Mapped [ChangeRequestState] to be exported to a schema.
     *
     * @property status Mapped column for [ChangeRequestState.status].
     * @property membershipId Mapped column for [ChangeRequestState.membershipId].
     */
    @Entity
    @Table(name = "change_request_state")
    class PersistentChangeRequestState(
            @Column(name = "status")
            val status: ChangeRequestStatus,
            @Column(name = "membership_id")
            val membershipId: String
    ) : PersistentState()
}