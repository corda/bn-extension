package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.MembershipContract
import net.corda.bn.schemas.GroupStateSchemaV1
import net.corda.bn.schemas.MembershipStateSchemaV1
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.Builder.equal
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

@InitiatingFlow
@StartableByRPC
class UpdateCordaIdentityFlow(
        private val membershipId: UniqueIdentifier,
        private val notary: Party? = null
) : MembershipManagementFlow<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        auditLogger.info("$ourIdentity started updating Corda identity of member with $membershipId membership ID")

        val membership = getMembership(membershipId)
        val name = membership.state.data.identity.cordaIdentity.name
        val newIdentity = serviceHub.identityService.wellKnownPartyFromX500Name(name)
                ?: throw FlowException("Party with $name X500 name doesn't exist")

        // check whether party is authorised to initiate flow
        val networkId = membership.state.data.networkId
        val flowName = javaClass.name
        getMembership(networkId, ourIdentity).apply {
            if (!state.data.isActive()) {
                throw IllegalMembershipStatusException("Membership owned by $ourIdentity is not active")
            }
            if (!state.data.canModifyGroups()) {
                throw MembershipAuthorisationException("$ourIdentity is not authorised to run $flowName")
            }
        }

        // fetch signers
        val authorisedMemberships = getMembersAuthorisedToModifyMembership(networkId)
        val signers = authorisedMemberships.filter {
            it.state.data.isActive()
        }.map {
            it.state.data.identity.cordaIdentity
        }.toSet() + membership.state.data.identity.cordaIdentity

        // building transaction
        val outputMembership = membership.state.data.run {
            copy(identity = identity.copy(cordaIdentity = newIdentity), modified = serviceHub.clock.instant())
        }
        val requiredSigners = signers.map { it.owningKey }
        val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(membership)
                .addOutputState(outputMembership)
                .addCommand(MembershipContract.Commands.ModifyCordaIdentity(requiredSigners), requiredSigners)
        builder.verify(serviceHub)

        // collect signatures and finalise transaction
        val observerSessions = (outputMembership.participants - ourIdentity).map { initiateFlow(it) }
        val finalisedTransaction = collectSignaturesAndFinaliseTransaction(builder, observerSessions, signers.toList())

        // sync all groups modified member is part of
        getAllBusinessNetworkGroups(networkId).filter {
            membership.state.data.identity.cordaIdentity in it.state.data.participants
        }.map { group ->
            val memberships = group.state.data.participants.map {
                getMembership(networkId, it)
            }

            subFlow(ModifyGroupFlow(group.state.data.linearId, null, memberships.map { it.state.data.linearId }.toSet(), notary))
        }

        auditLogger.info("$ourIdentity successfully updated Corda identity of member with $membershipId membership ID")

        return finalisedTransaction
    }

    private fun getMembership(linearId: UniqueIdentifier): StateAndRef<MembershipState> {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId)))
        return serviceHub.vaultService.queryBy<MembershipState>(criteria).states.maxBy { it.state.data.modified }
                ?: throw MembershipNotFoundException("Membership state with $linearId linear ID doesn't exist")
    }

    private fun getMembership(networkId: String, party: Party): StateAndRef<MembershipState> {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(QueryCriteria.VaultCustomQueryCriteria(builder { MembershipStateSchemaV1.PersistentMembershipState::networkId.equal(networkId) }))
                .and(QueryCriteria.VaultCustomQueryCriteria(builder { MembershipStateSchemaV1.PersistentMembershipState::cordaIdentity.equal(party) }))
        return serviceHub.vaultService.queryBy<MembershipState>(criteria).states.maxBy { it.state.data.modified }
                ?: throw MembershipNotFoundException("$party is not member of a Business Network with $networkId network ID")
    }

    private fun getMembersAuthorisedToModifyMembership(networkId: String): List<StateAndRef<MembershipState>> {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(QueryCriteria.VaultCustomQueryCriteria(builder { MembershipStateSchemaV1.PersistentMembershipState::networkId.equal(networkId) }))
        return serviceHub.vaultService.queryBy<MembershipState>(criteria).states.filter { it.state.data.canModifyMembership() }
    }

    private fun getAllBusinessNetworkGroups(networkId: String): List<StateAndRef<GroupState>> {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(QueryCriteria.VaultCustomQueryCriteria(builder { GroupStateSchemaV1.PersistentGroupState::networkId.equal(networkId) }))
        return serviceHub.vaultService.queryBy<GroupState>(criteria).states
    }
}

@InitiatedBy(UpdateCordaIdentityFlow::class)
class UpdateCordaIdentityResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is MembershipContract.Commands.ModifyCordaIdentity) {
                throw FlowException("Only ModifyCordaIdentity command is allowed")
            }
        }
    }
}