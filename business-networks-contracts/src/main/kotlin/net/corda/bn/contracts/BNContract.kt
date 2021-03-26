package net.corda.bn.contracts

import net.corda.bn.states.MembershipState
import net.corda.core.contracts.Contract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.security.PublicKey

/**
 * Interface with shared helper methods for [MembershipContract] and [GroupContract].
 */
interface BNContract : Contract {

    /**
     * Contract verification check based on passed transaction initiator.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param networkId ID of the Business Network.
     * @param evolvedState State that is evolves in the transaction.
     * @param requiredSigners List of required transaction signers.
     * @param authorisationMethod Method which does actual authorisation check over membership.
     *
     * @return Membership state of the initiator.
     */
    fun verifyInitiator(
        tx: LedgerTransaction,
        networkId: String,
        evolvedState: ContractState,
        requiredSigners: List<PublicKey>,
        authorisationMethod: (MembershipState) -> Boolean
    ): MembershipState = requireThat {
        val initiator = (tx.referenceStates.singleOrNull() ?: tx.inputStates.single()) as MembershipState

        "Initiator must belong to the same Business Network as the transaction membership" using (initiator.networkId == networkId)
        "Initiator must be active member of the Business Network" using (initiator.isActive())
        "Initiator must be authorised to build the membership modification transactions" using (authorisationMethod(
            initiator
        ))
        "Initiator must be one of the participants of the modified state" using (initiator.identity.cordaIdentity in evolvedState.participants)
        "Initiator must be one of the required signers" using (initiator.identity.cordaIdentity.owningKey in requiredSigners)

        initiator
    }
}