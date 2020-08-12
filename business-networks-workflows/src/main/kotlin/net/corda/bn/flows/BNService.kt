package net.corda.bn.flows

import net.corda.bn.schemas.GroupStateSchemaV1
import net.corda.bn.schemas.MembershipStateSchemaV1
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import net.corda.core.node.AppServiceHub
import net.corda.core.node.services.CordaService
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.serialization.SingletonSerializeAsToken

/**
 * Service which handles all Business Network related vault queries.
 *
 * Each method querying vault for Business Network information must be included here.
 */
@CordaService
class BNService(private val serviceHub: AppServiceHub) : SingletonSerializeAsToken() {

    /** Identity of Business Network Service caller. **/
    private val ourIdentity = serviceHub.myInfo.legalIdentities.first()

    /**
     * Checks whether Business Network with [networkId] ID exists.
     *
     * @param networkId ID of the Business Network.
     */
    fun businessNetworkExists(networkId: String): Boolean {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)
                .and(membershipNetworkIdCriteria(networkId))
        return serviceHub.vaultService.queryBy<MembershipState>(criteria).states.isNotEmpty()
    }

    /**
     * Checks whether [party] is member of Business Network with [networkId] ID.
     *
     * @param networkId ID of the Business Network.
     * @param party Identity of the potential member.
     */
    fun isBusinessNetworkMember(networkId: String, party: Party): Boolean {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(membershipNetworkIdCriteria(networkId))
                .and(identityCriteria(party))
        return serviceHub.vaultService.queryBy<MembershipState>(criteria).states.isNotEmpty()
    }

    /**
     * Queries for membership with [party] identity inside Business Network with [networkId] ID.
     *
     * @param networkId ID of the Business Network.
     * @param party Identity of the member.
     *
     * @return Membership state of member matching the query. If that member doesn't exist, returns [null].
     *
     * @throws IllegalStateException If the caller is not member of the Business Network with [networkId] ID or is not
     * part of any Business Network Group that [party] is part of.
     */
    fun getMembership(networkId: String, party: Party): StateAndRef<MembershipState>? {
        check(isBusinessNetworkMember(networkId, ourIdentity)) { "Caller is not member of the Business Network with $networkId ID" }

        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(membershipNetworkIdCriteria(networkId))
                .and(identityCriteria(party))
        val states = serviceHub.vaultService.queryBy<MembershipState>(criteria).states
        return states.maxBy { it.state.data.modified }?.apply {
            check(ourIdentity in state.data.participants) { "Caller is not part of any Business Network Group that $party is part of" }
        }
    }

    /**
     * Queries for membership with [linearId] linear ID.
     *
     * @param linearId Linear ID of the [MembershipState].
     *
     * @return Membership state matching the query. If that membership doesn't exist, returns [null].
     *
     * @throws IllegalStateException If the caller is not member of the Business Network or not part of any Business
     * Network Group that member with [linearId] ID is part of.
     */
    fun getMembership(linearId: UniqueIdentifier): StateAndRef<MembershipState>? {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(linearIdCriteria(linearId))
        val states = serviceHub.vaultService.queryBy<MembershipState>(criteria).states
        return states.maxBy { it.state.data.modified }?.apply {
            check(isBusinessNetworkMember(state.data.networkId, ourIdentity)) { "Caller is not member of the Business Network with ${state.data.networkId} ID" }
            check(ourIdentity in state.data.participants) { "Caller is not part of any Business Network Group that ${state.data.identity.cordaIdentity} is part of" }
        }
    }

    /**
     * Queries for all the membership states inside Business Network with [networkId] with one of [statuses] that are
     * part of at least one common group as the caller.
     *
     * @param networkId ID of the Business Network.
     * @param statuses [MembershipStatus] of the memberships to be fetched.
     *
     * @return List of state and ref pairs of memberships matching the query.
     *
     * @throws IllegalStateException If the caller is not member of the Business Network with [networkId] ID.
     */
    fun getAllMembershipsWithStatus(networkId: String, vararg statuses: MembershipStatus): List<StateAndRef<MembershipState>> {
        check(isBusinessNetworkMember(networkId, ourIdentity)) { "Caller is not member of the Business Network with $networkId ID" }

        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(membershipNetworkIdCriteria(networkId))
                .and(statusCriteria(statuses.toList()))
        return serviceHub.vaultService.queryBy<MembershipState>(criteria).states.filter {
            ourIdentity in it.state.data.participants
        }
    }

    /**
     * Queries for all members inside Business Network with [networkId] ID authorised to modify membership
     * (can activate, suspend or revoke membership) that are part of at least one common group as the caller.
     *
     * @param networkId ID of the Business Network.
     *
     * @return List of state and ref pairs of authorised members' membership states.
     *
     * @throws IllegalStateException If the caller is not member of the Business Network with [networkId] ID.
     */
    fun getMembersAuthorisedToModifyMembership(networkId: String): List<StateAndRef<MembershipState>> = getAllMembershipsWithStatus(
            networkId,
            MembershipStatus.ACTIVE, MembershipStatus.SUSPENDED
    ).filter {
        it.state.data.canModifyMembership()
    }

    /**
     * Checks whether Business Network Group with [groupId] ID exists and caller is part of it.
     *
     * @param groupId ID of the Business Network Group.
     */
    fun businessNetworkGroupExists(groupId: UniqueIdentifier): Boolean {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)
                .and(linearIdCriteria(groupId))
        val state = serviceHub.vaultService.queryBy<GroupState>(criteria).states.map { it.state.data }.maxBy { it.modified }
        return state != null && ourIdentity in state.participants
    }

    /**
     * Checks whether Business Network Group with [groupName] name exists in Business Network with [networkId] ID and
     * caller is part of it.
     *
     * @param networkId ID of the Business Network.
     * @param groupName Name of the Business Network Group.
     *
     * @throws IllegalStateException If the caller is not member of the Business Network with [networkId] ID.
     */
    fun businessNetworkGroupExists(networkId: String, groupName: String): Boolean {
        check(isBusinessNetworkMember(networkId, ourIdentity)) { "Caller is not member of the Business Network with $networkId ID" }

        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.ALL)
                .and(groupNetworkIdCriteria(networkId))
                .and(groupNameCriteria(groupName))
        val state = serviceHub.vaultService.queryBy<GroupState>(criteria).states.map { it.state.data }.maxBy { it.modified }
        return state != null && ourIdentity in state.participants
    }

    /**
     * Queries for Business Network Group with [groupId] ID.
     *
     * @param groupId ID of the Business Network Group.
     *
     * @return Business Network Group matching the query. If that group doesn't exist, return [null].
     *
     * @throws IllegalStateException If the caller is not part of the Business Network Group with [groupId] ID.
     */
    fun getBusinessNetworkGroup(groupId: UniqueIdentifier): StateAndRef<GroupState>? {
        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(linearIdCriteria(groupId))
        val states = serviceHub.vaultService.queryBy<GroupState>(criteria).states
        return states.maxBy { it.state.data.modified }?.apply {
            check(ourIdentity in state.data.participants) { "Caller is not part of the Business Network Group with $groupId ID" }
        }
    }

    /**
     * Queries for Business Network Group with [groupName] name in Business Network with [networkId] ID.
     *
     * @param networkId ID of the Business Network.
     * @param groupName Name of the Business Network Group.
     *
     * @return Business Network Group matching the query. If that group doesn't exist, return [null].
     *
     * @throws IllegalStateException If the caller is not member of the Business Network with [networkId] ID or part of
     * the Business Network Group with [groupName] name.
     */
    fun getBusinessNetworkGroup(networkId: String, groupName: String): StateAndRef<GroupState>? {
        check(isBusinessNetworkMember(networkId, ourIdentity)) { "Caller is not member of the Business Network with $networkId ID" }

        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(groupNetworkIdCriteria(networkId))
                .and(groupNameCriteria(groupName))
        val states = serviceHub.vaultService.queryBy<GroupState>(criteria).states
        return states.maxBy { it.state.data.modified }?.apply {
            check(ourIdentity in state.data.participants) { "Caller is not part of the Business Network Group with $groupName name" }
        }
    }

    /**
     * Queries for all Business Network Groups inside Business Network with [networkId] ID that the caller is part of.
     *
     * @param networkId ID of the Business Network.
     *
     * @return List of state and ref pairs of Business Network Groups.
     *
     * @throws IllegalStateException If the caller is not member of the Business Network with [networkId] ID.
     */
    fun getAllBusinessNetworkGroups(networkId: String): List<StateAndRef<GroupState>> {
        check(isBusinessNetworkMember(networkId, ourIdentity)) { "Caller is not member of the Business Network with $networkId ID" }

        val criteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
                .and(groupNetworkIdCriteria(networkId))
        return serviceHub.vaultService.queryBy<GroupState>(criteria).states.filter {
            ourIdentity in it.state.data.participants
        }
    }

    /** Instantiates custom vault query criteria for finding membership with given [networkId]. **/
    private fun membershipNetworkIdCriteria(networkId: String) = QueryCriteria.VaultCustomQueryCriteria(builder { MembershipStateSchemaV1.PersistentMembershipState::networkId.equal(networkId) })

    /** Instantiates custom vault query criteria for finding Business Network Group with given [networkId]. **/
    private fun groupNetworkIdCriteria(networkId: String) = QueryCriteria.VaultCustomQueryCriteria(builder { GroupStateSchemaV1.PersistentGroupState::networkId.equal(networkId) })

    /** Instantiates custom vault query criteria for finding membership with given [cordaIdentity]. **/
    private fun identityCriteria(cordaIdentity: Party) = QueryCriteria.VaultCustomQueryCriteria(builder { MembershipStateSchemaV1.PersistentMembershipState::cordaIdentity.equal(cordaIdentity) })

    /** Instantiates custom vault query criteria for finding membership with any of given [statuses]. **/
    private fun statusCriteria(statuses: List<MembershipStatus>) = QueryCriteria.VaultCustomQueryCriteria(builder { MembershipStateSchemaV1.PersistentMembershipState::status.`in`(statuses) })

    /** Instantiates custom vault query criteria for finding Business Network Group given [groupName]. **/
    private fun groupNameCriteria(groupName: String) = QueryCriteria.VaultCustomQueryCriteria(builder { GroupStateSchemaV1.PersistentGroupState::name.equal(groupName) })

    /** Instantiates custom vault query criteria for finding linear state with given [linearId]. **/
    private fun linearIdCriteria(linearId: UniqueIdentifier) = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
}
