package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.ChangeRequestContract
import net.corda.bn.states.ChangeRequestState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.FlowSession
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/**
 * This flow is initiated by an authorised member who wants to mark a [MembershipModificationRequest] as historic.
 * Takes an existing [ChangeRequestState] and consumes it.
 *
 * @property requestId The ID of the request which needs to be consumed.
 * @property notary Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.
 * */
@InitiatingFlow
@StartableByRPC
class DeleteAttributeChangeRequestFlow(
        private val requestId: UniqueIdentifier,
        private val notary: Party? = null
) : MembershipManagementFlow<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val bnService = serviceHub.cordaService(BNService::class.java)

        val membershipChangeRequest = bnService.getMembershipChangeRequest(requestId)
                ?: throw MembershipChangeRequestNotFoundException("Could not find change request state with $requestId request ID")

        val participants = membershipChangeRequest.state.data.participants

        val requiredSigners = participants.map { it.owningKey }
        val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(membershipChangeRequest)
                .addCommand(ChangeRequestContract.Commands.Delete(requiredSigners), requiredSigners)
        builder.verify(serviceHub)

        // collect signatures and finalise transaction
        val observerSessions = (participants - ourIdentity).map { initiateFlow(it) }
        val finalisedTransaction = collectSignaturesAndFinaliseTransaction(builder, observerSessions, (participants - ourIdentity) as List<Party>)

        auditLogger.info("$ourIdentity successfully archived membership changes for request with $requestId request ID")

        return finalisedTransaction
    }
}

@InitiatedBy(DeleteAttributeChangeRequestFlow::class)
class DeleteAttributeChangeRequestFlowResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is ChangeRequestContract.Commands.Delete) {
                throw FlowException("Only Delete command is allowed")
            }
        }
    }
}