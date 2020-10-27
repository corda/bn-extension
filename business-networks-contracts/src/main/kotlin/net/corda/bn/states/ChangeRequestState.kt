package net.corda.bn.states

import net.corda.bn.contracts.ChangeRequestContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.serialization.CordaSerializable
import java.time.Instant

/**
 * Represents a Change Request on ledger.
 *
 * @property status The status of the request.
 * @property membershipId ID of the membership which needs to be updated.
 * @property proposedRoleChange A set of proposed [BNRole] which has to overwrite the existing role set.
 * @property proposedBusinessIdentityChange A proposed [BNIdentity] which has to overwrite the existing identity.
 * @property issued Timestamp when the state has been issued.
 * @property modified Timestamp when the state has been modified last time.
 * */
@BelongsToContract(ChangeRequestContract::class)
data class ChangeRequestState(
        val status: ChangeRequestStatus,
        val membershipId: UniqueIdentifier,
        val proposedRoleChange: Set<BNRole>? = null,
        val proposedBusinessIdentityChange: BNIdentity? = null,
        val issued: Instant = Instant.now(),
        val modified: Instant = issued,
        override val linearId: UniqueIdentifier = UniqueIdentifier(),
        override val participants: List<AbstractParty>
) : LinearState {

    /** Indicates whether membership is in [ChangeRequestStatus.PENDING] status. **/
    fun isPending() = status == ChangeRequestStatus.PENDING

    /** Indicates whether membership is in [ChangeRequestStatus.APPROVED] status. **/
    fun isApproved() = status == ChangeRequestStatus.APPROVED

    /** Indicates whether membership is in [ChangeRequestStatus.DECLINED] status. **/
    fun isDeclined() = status == ChangeRequestStatus.DECLINED
}

/**
 * Statuses that attribute change requests can go through.
 */
@CordaSerializable
enum class ChangeRequestStatus {
    /**
     * Newly submitted change request which hasn't been approved by an authorised member yet.
     */
    PENDING,

    /**
     * Approved change requests will overwrite the existing [MembershipState].
     */
    APPROVED,

    /**
     * Declined changes won't affect the existing [MembershipState].
     */
    DECLINED
}