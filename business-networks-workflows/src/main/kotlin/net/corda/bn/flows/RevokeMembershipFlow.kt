package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.MembershipContract
import net.corda.bn.states.AdminPermission
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/**
 * This flow is initiated by any member authorised to revoke membership. Queries for the membership with [membershipId] linear ID and
 * marks it historic. Transaction is signed by all active members authorised to modify membership and stored on ledgers of all state's
 * participants.
 *
 * @property membershipId ID of the membership to be revoked.
 * @property notary Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.
 */
@InitiatingFlow
@StartableByRPC
class RevokeMembershipFlow(private val membershipId: UniqueIdentifier, private val notary: Party? = null) : MembershipManagementFlow<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        auditLogger.info("$ourIdentity started revocation of member with $membershipId membership ID")

        val bnService = serviceHub.cordaService(BNService::class.java)
        val membership = bnService.getMembership(membershipId)
                ?: throw MembershipNotFoundException("Membership state with $membershipId linear ID doesn't exist")

        // check whether party is authorised to initiate flow
        val networkId = membership.state.data.networkId
        authorise(networkId, bnService) { it.canRevokeMembership() }

        // ensure we're not suspending ourselves
        if (ourIdentity == membership.state.data.identity.cordaIdentity) {
            throw IllegalFlowArgumentException("Member cannot revoke itself")
        }


        // check if the result of this flow will leave the network without sufficient permissions across its authorised members
        val removedPermissions = membership.state.data.roles.map { it.permissions }.flatten().filterIsInstance<AdminPermission>().toSet()
        if (!bnService.safeToRemovePermissions(networkId, removedPermissions)) {
            throw InvalidBusinessNetworkStateException("This flow attempts to remove remaining $removedPermissions from the network")
        }

        // fetch signers
        val authorisedMemberships = bnService.getMembersAuthorisedToModifyMembership(networkId)
        val signers = authorisedMemberships.filter { it.state.data.isActive() }.map { it.state.data.identity.cordaIdentity } - membership.state.data.identity.cordaIdentity

        // remove revoked member from all the groups he is participant of
        val revokedMemberIdentity = membership.state.data.identity.cordaIdentity
        bnService.getAllBusinessNetworkGroups(networkId).filter {
            revokedMemberIdentity in it.state.data.participants
        }.map { group ->
            val memberships = (group.state.data.participants - revokedMemberIdentity).map {
                bnService.getMembership(networkId, it)
                        ?: throw MembershipNotFoundException("Cannot find membership with $it linear ID")
            }.toSet()

            subFlow(ModifyGroupInternalFlow(group.state.data.linearId, null, memberships.map { it.state.data.linearId }.toSet(), false, notary))
            syncMembershipsParticipants(networkId, memberships, signers, bnService, notary)
        }

        // building transaction
        val requiredSigners = signers.map { it.owningKey }
        val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(membership)
                .addCommand(MembershipContract.Commands.Revoke(requiredSigners), requiredSigners)
        builder.verify(serviceHub)

        // collect signatures and finalise transaction
        val observerSessions = (membership.state.data.participants - ourIdentity).map { initiateFlow(it) }
        val finalisedTransaction = collectSignaturesAndFinaliseTransaction(builder, observerSessions, signers)

        auditLogger.info("$ourIdentity successfully revoked member with $membershipId membership ID")

        return finalisedTransaction
    }
}

@InitiatedBy(RevokeMembershipFlow::class)
class RevokeMembershipFlowResponder(val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is MembershipContract.Commands.Revoke) {
                throw FlowException("Only Revoke command is allowed")
            }
        }
    }
}
