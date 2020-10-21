package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.ChangeRequestContract
import net.corda.bn.states.ChangeRequestState
import net.corda.bn.states.ChangeRequestStatus
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.FlowSession
import net.corda.core.identity.Party
import net.corda.core.internal.uncheckedCast
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/**
 * This flow is initiated by an authorised member who wants to accept a [MembershipModificationRequest] to change
 * a members attributes.
 * Modifies an existing [ChangeRequestState]'s status to APPROVED on initiator's and authorised member's ledgers and
 * executes the changes.
 *
 * @property requestId The ID of the request which needs to be accepted.
 * @property notary Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.
 * */
@InitiatingFlow
@StartableByRPC
class AcceptMembershipAttributeChangeFlow(
        private val requestId: UniqueIdentifier,
        private val notary: Party? = null
) : MembershipManagementFlow<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        val bnService = serviceHub.cordaService(BNService::class.java)

        val membershipChangeRequest = bnService.getMembershipChangeRequest(requestId)
                ?: throw MembershipChangeRequestNotFoundException("Could not find change request state with $requestId request ID")

        val membershipId = membershipChangeRequest.state.data.membershipId

        auditLogger.info("$ourIdentity started accepting and activating membership attribute changes of " +
                "member with $membershipId membership ID for request with $requestId request ID")

        bnService.getMembership(membershipId)
                ?: throw MembershipNotFoundException("Membership state with $membershipId linear ID doesn't exist")

        val membershipChangeData = membershipChangeRequest.state.data

        // check whether party is authorised to initiate flow
        val networkId = bnService.getMembership(membershipChangeData.membershipId)!!.state.data.networkId

        if (membershipChangeData.pendingBusinessIdentityChange != null) {
            authorise(networkId, bnService) { it.canModifyBusinessIdentity() }
            // Call the modify business identity flow if the business identity has to be modified
            subFlow(ModifyBusinessIdentityFlow(membershipId, membershipChangeData.pendingBusinessIdentityChange!!, notary))
        }
        if (membershipChangeData.pendingRoleChange != null) {
            authorise(networkId, bnService) { it.canModifyRoles() }
            // Call the modify roles flow if the roles have to be modified
            subFlow(ModifyRolesFlow(membershipId, membershipChangeData.pendingRoleChange!!, notary))
        }

        // building transaction
        val outputMembershipChangeRequest = membershipChangeRequest.state.data.copy(status = ChangeRequestStatus.APPROVED, modified = serviceHub.clock.instant())
        val signers = outputMembershipChangeRequest.participants - ourIdentity
        val requiredSigners = signers.map { it.owningKey }
        val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(membershipChangeRequest)
                .addOutputState(outputMembershipChangeRequest)
                .addCommand(ChangeRequestContract.Commands.Approve(requiredSigners), requiredSigners)
        builder.verify(serviceHub)

        // collect signatures and finalise transaction
        val observerSessions = signers.map { initiateFlow(it) }
        val finalisedTransaction = collectSignaturesAndFinaliseTransaction(builder, observerSessions, uncheckedCast(signers))

        auditLogger.info("$ourIdentity successfully approved membership changes for member with $membershipId membership ID")

        return finalisedTransaction
    }
}

@InitiatedBy(AcceptMembershipAttributeChangeFlow::class)
class AcceptMembershipAttributeChangeResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is ChangeRequestContract.Commands.Approve) {
                throw FlowException("Only Approve command is allowed")
            }
        }
    }
}