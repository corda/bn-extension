package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.MembershipContract
import net.corda.bn.states.BNIdentity
import net.corda.bn.states.MembershipIdentity
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.ReceiveFinalityFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap

@CordaSerializable
data class MembershipRequest(val networkId: String, val businessIdentity: BNIdentity?, val notary: Party?)

/**
 * This flow is initiated by new potential member who requests membership activation from authorised Business Network member. Issues
 * new pending [MembershipState] on potential member and all authorised members' ledgers.
 *
 * @property authorisedParty Identity of authorised member from whom the membership activation is requested.
 * @property networkId ID of the Business Network that potential new member wants to join.
 * @property businessIdentity Custom business identity to be given to membership.
 * @property notary Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.
 *
 * @throws DuplicateBusinessNetworkRequestException If there is race condition between flow calls which would cause
 * duplicate pending membership issuance.
 */
@InitiatingFlow
@StartableByRPC
class RequestMembershipFlow(
        private val authorisedParty: Party,
        private val networkId: String,
        private val businessIdentity: BNIdentity? = null,
        private val notary: Party? = null
) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        // check whether the initiator is already member of given Business Network
        val bnService = serviceHub.cordaService(BNService::class.java)
        if (bnService.isBusinessNetworkMember(networkId, ourIdentity)) {
            throw FlowException("Initiator is already a member of Business Network with $networkId ID")
        }

        // send request to authorised member
        val authorisedPartySession = initiateFlow(authorisedParty)
        authorisedPartySession.send(MembershipRequest(networkId, businessIdentity, notary))

        // sign transaction
        val signResponder = object : SignTransactionFlow(authorisedPartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                val command = stx.tx.commands.single()
                if (command.value !is MembershipContract.Commands.Request) {
                    throw FlowException("Only Request command is allowed")
                }

                val membershipState = stx.tx.outputs.single().data as MembershipState
                if (ourIdentity != membershipState.identity.cordaIdentity) {
                    throw IllegalArgumentException("Membership identity does not match the one of the initiator")
                }

                stx.toLedgerTransaction(serviceHub, false).verify()
            }
        }
        val stx = subFlow(signResponder)

        // receive finality flow
        return subFlow(ReceiveFinalityFlow(authorisedPartySession, stx.id))
    }
}

@InitiatingFlow
@InitiatedBy(RequestMembershipFlow::class)
class RequestMembershipFlowResponder(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        // receive network ID
        val (networkId, businessIdentity, notary) = session.receive<MembershipRequest>().unwrap { it }

        // check whether party is authorised to activate membership
        val bnService = serviceHub.cordaService(BNService::class.java)
        authorise(networkId, bnService) { it.canActivateMembership() }

        val counterparty = session.counterparty
        if (bnService.isBusinessNetworkMember(networkId, counterparty)) {
            throw FlowException("$counterparty is already a member of Business Network with $networkId ID")
        }

        // creating pending membership lock so no multiple requests with same data can be made in-flight
        bnService.lockStorage.createLock(BNRequestType.PENDING_MEMBERSHIP, counterparty.toString()) {
            logger.error("Error when trying to create a pending membership request for $counterparty")
        }

        try {
            // fetch observers
            val authorisedMemberships = bnService.getMembersAuthorisedToModifyMembership(networkId)
            val observers = (authorisedMemberships.map { it.state.data.identity.cordaIdentity } - ourIdentity).toSet()

            // build transaction
            val membershipState = MembershipState(
                    identity = MembershipIdentity(counterparty, businessIdentity),
                    networkId = networkId,
                    status = MembershipStatus.PENDING,
                    issuer = ourIdentity,
                    participants = (observers + ourIdentity + counterparty).toList()
            )
            val requiredSigners = listOf(ourIdentity.owningKey, counterparty.owningKey)
            val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                    .addOutputState(membershipState)
                    .addCommand(MembershipContract.Commands.Request(requiredSigners), requiredSigners)
            builder.verify(serviceHub)

            // sign transaction
            val selfSignedTransaction = serviceHub.signInitialTransaction(builder)
            val allSignedTransaction = subFlow(CollectSignaturesFlow(selfSignedTransaction, listOf(session)))

            // finalise transaction
            val observerSessions = observers.map { initiateFlow(it) }.toSet()
            subFlow(FinalityFlow(allSignedTransaction, observerSessions + session))
        } finally {
            // deleting previously created lock since all of the changes are persisted on ledger
            bnService.lockStorage.deleteLock(BNRequestType.PENDING_MEMBERSHIP, counterparty.toString()) {
                logger.warn("Error when trying to delete a pending membership request for $counterparty")
            }
        }
    }
}

@InitiatedBy(RequestMembershipFlowResponder::class)
class RequestMembershipObserverFlow(private val session: FlowSession) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        subFlow(ReceiveFinalityFlow(session))
    }
}
