package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.ChangeRequestContract
import net.corda.bn.states.BNIdentity
import net.corda.bn.states.BNRole
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import net.corda.bn.states.ChangeRequestState
import net.corda.bn.states.ChangeRequestStatus
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.ReceiveFinalityFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.FlowSession
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow

@CordaSerializable
data class MembershipModificationRequest(
        val membershipId: UniqueIdentifier,
        val businessIdentity: BNIdentity?,
        val roles: Set<BNRole>?,
        val notary: Party?
)

/**
 * This flow is initiated by a member who wants to issue a [MembershipModificationRequest] to change its attributes.
 * Creates a new [ChangeRequestState] with PENDING status on initiator's and authorised member's ledgers.
 *
 * @property authorisedParty Identity of authorised member from whom the change request approval/rejection is requested.
 * @property networkId ID of the Business Network that members are part of.
 * @property businessIdentity The business identity change member wants to request.
 * @property roles The role change member wants to request.
 * @property notary Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.
 * */
@InitiatingFlow
@StartableByRPC
class RequestMembershipAttributeChangeFlow(
        private val authorisedParty: Party,
        private val networkId: String,
        private val businessIdentity: BNIdentity? = null,
        private val roles: Set<BNRole>? = null,
        private val notary: Party? = null
) : MembershipManagementFlow<SignedTransaction>() {

    @Suppress("ComplexMethod")
    @Suspendable
    override fun call() : SignedTransaction {
        auditLogger.info(
                "$ourIdentity started creating membership attribute changes request from $authorisedParty authorised party " +
                 "in Business Network with network ID $networkId " +
                        (if(businessIdentity != null) "to have new $businessIdentity business identity " else "")  +
                        (if(roles != null) "to have new $roles roles set" else "")
        )

        val bnService = serviceHub.cordaService(BNService::class.java)

        if (!bnService.businessNetworkExists(networkId)) {
            throw BusinessNetworkNotFoundException("Business Network with $networkId doesn't exist")
        }

        val ourMembership = bnService.getMembership(networkId, ourIdentity)
                ?: throw MembershipNotFoundException("$ourIdentity is not a member of a business network")

        val authorisedMembership = bnService.getMembership(networkId, authorisedParty)
                ?: throw MembershipNotFoundException("$authorisedParty is not a member of a business network")

        if(businessIdentity != null) {
            if(!authorisedMembership.state.data.canModifyBusinessIdentity())
                throw MembershipAuthorisationException("$authorisedParty does not have permission to modify business identity")
        }

        if(roles != null) {
            if(!authorisedMembership.state.data.canModifyRoles())
                throw MembershipAuthorisationException("$authorisedParty does not have permission to modify roles")
        }

        // send the modification request to authorised member
        val membershipId = ourMembership.state.data.linearId
        val authorisedPartySession = initiateFlow(authorisedParty)
        authorisedPartySession.send(MembershipModificationRequest(membershipId, businessIdentity, roles, notary))

        // sign transaction
        val signResponder = object : SignTransactionFlow(authorisedPartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val command = stx.tx.commands.single()
                if (command.value !is ChangeRequestContract.Commands.Request) {
                    throw FlowException("Only Request command is allowed")
                }

                stx.toLedgerTransaction(serviceHub, false).verify()
            }
        }

        val stx = subFlow(signResponder).also {
            auditLogger.info("$ourIdentity signed transaction ${it.tx} built by $authorisedParty")
        }

        // receive finality flow
        val finalisedTransaction = subFlow(ReceiveFinalityFlow(authorisedPartySession, stx.id))

        auditLogger.info("$ourIdentity successfully submitted membership " +
                "attribute change request in a Business Network with ${this.networkId} network ID")

        return finalisedTransaction
    }
}

@InitiatedBy(RequestMembershipAttributeChangeFlow::class)
private class RequestMembershipAttributeChangeResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        val counterParty = session.counterparty
        val (membershipId, businessIdentity, roles, notary) =
                session.receive<MembershipModificationRequest>().unwrap { it }

        val bnService = serviceHub.cordaService(BNService::class.java)

        // creating pending membership attribute change request lock so no multiple requests with same data can be made in-flight
        bnService.lockStorage.createLock(BNRequestType.PENDING_ATTRIBUTE_CHANGE_REQUEST, counterParty.toString()) {
            logger.error("Error when trying to create a pending membership attribute change request for $counterParty")
        }

        try {
            // build transaction
            val modificationRequest = ChangeRequestState(
                    status = ChangeRequestStatus.PENDING,
                    membershipId = membershipId,
                    proposedRoleChange = roles,
                    proposedBusinessIdentityChange = businessIdentity,
                    participants = listOf(ourIdentity, counterParty)
            )

            val requiredSigners = listOf(ourIdentity.owningKey, counterParty.owningKey)
            val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                    .addOutputState(modificationRequest)
                    .addCommand(ChangeRequestContract.Commands.Request(requiredSigners), requiredSigners)
            builder.verify(serviceHub)

            // sign transaction
            val selfSignedTransaction = serviceHub.signInitialTransaction(builder)
            val allSignedTransaction = subFlow(CollectSignaturesFlow(selfSignedTransaction, listOf(session)))

            // finalise transaction
            subFlow(FinalityFlow(allSignedTransaction, session))
        } finally {
            // deleting previously created lock since all of the changes are persisted on ledger
            bnService.lockStorage.deleteLock(BNRequestType.PENDING_ATTRIBUTE_CHANGE_REQUEST, counterParty.toString()) {
                logger.warn("Error when trying to delete a pending membership attribute change request for $counterParty")
            }
        }
    }

}

