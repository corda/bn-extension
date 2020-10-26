package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.GroupContract
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

        val updatedParticipantsList = membership.state.data.participants.map() {
            if (it.owningKey == membership.state.data.identity.cordaIdentity.owningKey) {
                newIdentity
            } else it
        }
        val outputMembership = membership.state.data.run {
            copy(identity = identity.copy(cordaIdentity = newIdentity), modified = serviceHub.clock.instant(),
                    participants = updatedParticipantsList)
        }

        // fetch signers
        val authorisedMemberships = getMembersAuthorisedToModifyMembership(networkId)
        val signers = authorisedMemberships.filter {
            it.state.data.isActive() && it.state != membership.state // Member's new identity is added at the end, avoid duplication
        }.map {
            it.state.data.identity.cordaIdentity
        }.toSet() + newIdentity

        // building transaction
        val requiredSigners = signers.map { it.owningKey }
        val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(membership)
                .addOutputState(outputMembership)
                .addCommand(MembershipContract.Commands.ModifyCordaIdentity(requiredSigners), requiredSigners)
        builder.verify(serviceHub)

        // collect signatures and finalise transaction
        val observerSessions = (outputMembership.participants - ourIdentity).map { initiateFlow(it) }
        val finalisedTransaction = collectSignaturesAndFinaliseTransaction(builder, observerSessions, signers.toList())


        // update pending memberships and memberships which are not in a group yet only if the member being changed has permissions to activate memberships or modify groups
        if(membership.state.data.canActivateMembership()) {
            subFlow(UpdatePendingMembershipsFlow(membership, notary))
        }
        // update groups
        subFlow(UpdateGroupMembersIdentityFlow(membership, notary))

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

@InitiatingFlow
private class UpdateGroupMembersIdentityFlow(
        private val changedMembership: StateAndRef<MembershipState>,
        private val notary: Party?
): MembershipManagementFlow<SignedTransaction?>() {

    @Suspendable
    override fun call(): SignedTransaction? {
        val networkId = changedMembership.state.data.networkId
        val name = changedMembership.state.data.identity.cordaIdentity.name
        val newIdentity = serviceHub.identityService.wellKnownPartyFromX500Name(name)
                ?: throw FlowException("Party with $name X500 name doesn't exist")
        val finalisedTransactions = getAllBusinessNetworkGroups(networkId).filter {
            changedMembership.state.data.identity.cordaIdentity in it.state.data.participants
        }.map { group ->
            val newParticipants = group.state.data.participants.map {
                if (it == changedMembership.state.data.identity.cordaIdentity) {
                    newIdentity
                } else {
                    it
                }
            }

            val authorisedMembers = getMembersAuthorisedToModifyGroups(networkId)
            val signers = authorisedMembers.filter { it.state.data.isActive() }.map { it.state.data.identity.cordaIdentity }
            val requiredSigners = signers.map { it.owningKey }
            val updatedGroup = group.state.data.copy(participants = newParticipants)
            val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                    .addInputState(group)
                    .addOutputState(updatedGroup)
                    .addCommand(GroupContract.Commands.Modify(requiredSigners), requiredSigners)
            builder.verify(serviceHub)

            // collect signatures and finalise transaction
            val observers = (updatedGroup.participants - ourIdentity).map {
                serviceHub.identityService.wellKnownPartyFromX500Name(it.nameOrNull())!!
            }
            val flowSessions = observers.toSet().map { initiateFlow(it) }
            val finalisedTransaction = collectSignaturesAndFinaliseTransaction(builder, flowSessions, observers)
            finalisedTransaction
        }

        return finalisedTransactions.firstOrNull()
    }

    private fun getAllBusinessNetworkGroups(networkId: String): List<StateAndRef<GroupState>> {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(QueryCriteria.VaultCustomQueryCriteria(builder { GroupStateSchemaV1.PersistentGroupState::networkId.equal(networkId) }))
        return serviceHub.vaultService.queryBy<GroupState>(criteria).states
    }

    private fun getMembersAuthorisedToModifyGroups(networkId: String): List<StateAndRef<MembershipState>> {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(QueryCriteria.VaultCustomQueryCriteria(builder { MembershipStateSchemaV1.PersistentMembershipState::networkId.equal(networkId) }))
        return serviceHub.vaultService.queryBy<MembershipState>(criteria).states.filter { it.state.data.canModifyGroups() }
    }
}

@InitiatedBy(UpdateGroupMembersIdentityFlow::class)
private class UpdateGroupMembersIdentityResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is GroupContract.Commands.Modify) {
                throw FlowException("Only ModifyCordaIdentity command is allowed")
            }
        }
    }
}

@InitiatingFlow
private class UpdatePendingMembershipsFlow(
        private val changedMembership: StateAndRef<MembershipState>,
        private val notary: Party?
) : MembershipManagementFlow<SignedTransaction?>() {

    @Suppress("UNCHECKED_CAST")
    @Suspendable
    override fun call(): SignedTransaction? {
        val networkId = changedMembership.state.data.networkId
        val name = changedMembership.state.data.identity.cordaIdentity.name
        val newIdentity = serviceHub.identityService.wellKnownPartyFromX500Name(name)
                ?: throw FlowException("Party with $name X500 name doesn't exist")

        val finalisedTransactions = getAllMemberships(networkId).filter {
            changedMembership.state.data.identity.cordaIdentity in it.state.data.participants
        }.map { membership ->
            val updatedParticipantsList = membership.state.data.participants.map {
                if (it.owningKey == changedMembership.state.data.identity.cordaIdentity.owningKey) {
                    newIdentity
                } else it
            }

            val outputMembership = membership.state.data.run {
                copy(modified = serviceHub.clock.instant(), participants = updatedParticipantsList)
            }

            // signers should all be in the participants list
            val requiredSigners = updatedParticipantsList.map { it.owningKey }
            val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                    .addInputState(membership)
                    .addOutputState(outputMembership)
                    .addCommand(MembershipContract.Commands.ModifyCordaIdentity(requiredSigners), requiredSigners)
            builder.verify(serviceHub)

            // collect signatures and finalise transaction
            // need to compose flow sessions based on local information not what's in a possibly stale membership state
            val observers = (outputMembership.participants - ourIdentity).map {
               serviceHub.identityService.wellKnownPartyFromX500Name(it.nameOrNull()!!)!!
            }
            val observerSessions = observers.map { initiateFlow(it) }
            val finalisedTransaction = collectSignaturesAndFinaliseTransaction(builder, observerSessions, observers)
            finalisedTransaction
        }

        return finalisedTransactions.firstOrNull()
    }

    private fun getAllMemberships(networkId: String): List<StateAndRef<MembershipState>> {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(QueryCriteria.VaultCustomQueryCriteria(builder { MembershipStateSchemaV1.PersistentMembershipState::networkId.equal(networkId) }))
        return serviceHub.vaultService.queryBy<MembershipState>(criteria).states
    }
}

@InitiatedBy(UpdatePendingMembershipsFlow::class)
private class UpdatePendingMembershipsResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is MembershipContract.Commands.ModifyCordaIdentity) {
                throw FlowException("Only ModifyParticipants command is allowed")
            }
        }
    }
}