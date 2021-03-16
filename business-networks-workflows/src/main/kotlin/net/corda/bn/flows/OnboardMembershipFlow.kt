package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.MembershipContract
import net.corda.bn.states.BNIdentity
import net.corda.bn.states.MembershipIdentity
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/**
 * This flow is initiated by any member authorised to activate membership. Issues new active [MembershipState] on
 * [onboardedParty] and all authorised members' ledgers. It is useful to call this flow when authorised party wants
 * to onboard new member without new member requiring to request membership by discovering a Business Network.
 *
 * @property networkId ID of the Business Network that member is onboarded to.
 * @property onboardedParty Identity of onboarded member.
 * @property businessIdentity Custom business identity to be given to onboarded membership.
 * @property notary Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.
 *
 * @throws DuplicateBusinessNetworkRequestException If there is race condition between flow calls which would cause
 * duplicate membership issuance.
 */
@InitiatingFlow
@StartableByRPC
class OnboardMembershipFlow(
        private val networkId: String,
        private val onboardedParty: Party,
        private val businessIdentity: BNIdentity? = null,
        private val notary: Party? = null
) : MembershipManagementFlow<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        auditLogger.info("$ourIdentity started onboarding $onboardedParty to a Business Network with $networkId")

        // check whether party is authorised to initiate flow
        val bnService = serviceHub.cordaService(BNService::class.java)
        val ourMembership = authorise(networkId, bnService) { it.canActivateMembership() }

        // check whether onboarded party is already member of given Business Network
        if (bnService.isBusinessNetworkMember(networkId, onboardedParty)) {
            throw FlowException("$onboardedParty is already member of Business Network with $networkId ID")
        }

        // creating pending membership lock so no multiple requests with same data can be made in-flight
        bnService.lockStorage.createLock(BNRequestType.PENDING_MEMBERSHIP, onboardedParty.toString()) {
            logger.error("Error when trying to create an onboarding membership request for $onboardedParty")
        }

        try {
            // fetch observers
            val authorisedMembers = bnService.getMembersAuthorisedToModifyMembership(networkId)
            val observers = (authorisedMembers.map { it.state.data.identity.cordaIdentity } - ourIdentity).toSet()

            // build transaction
            val membershipState = MembershipState(
                    identity = MembershipIdentity(onboardedParty, businessIdentity),
                    networkId = networkId,
                    status = MembershipStatus.ACTIVE,
                    issuer = ourIdentity,
                    participants = (observers + ourIdentity + onboardedParty).toList()
            )
            val requiredSigners = listOf(ourIdentity.owningKey, onboardedParty.owningKey)
            val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                    .addOutputState(membershipState)
                    .addCommand(MembershipContract.Commands.Onboard(requiredSigners), requiredSigners)
                    .addReferenceState(ourMembership.referenced())
            builder.verify(serviceHub)

            val observerSessions = (observers + onboardedParty).map { initiateFlow(it) }
            val finalisedTransaction = collectSignaturesAndFinaliseTransaction(builder, observerSessions, listOf(ourIdentity, onboardedParty))

            auditLogger.info("$ourIdentity successfully onboarded $onboardedParty to a Business Network with $networkId")

            return finalisedTransaction
        } finally {
            // deleting previously created lock since all of the changes are persisted on ledger
            bnService.lockStorage.deleteLock(BNRequestType.PENDING_MEMBERSHIP, onboardedParty.toString()) {
                logger.error("Error when trying to delete an onboarding membership request for $onboardedParty")
            }
        }
    }
}

@InitiatedBy(OnboardMembershipFlow::class)
class OnboardMembershipResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>/**/() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is MembershipContract.Commands.Onboard) {
                throw FlowException("Only Onboard command is allowed")
            }
        }
    }
}
