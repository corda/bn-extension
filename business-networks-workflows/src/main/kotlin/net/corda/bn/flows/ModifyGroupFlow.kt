package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.GroupContract
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/**
 * This flow is initiated by any member authorised to modify Business Network Groups. Queries for groups with [groupId] linear ID and
 * modifies their [GroupState.name] and [GroupState.participants] fields. Transaction is signed by all active members authorised to modify
 * membership and stored on ledgers of all group's participants.
 *
 * Memberships of new group participants are exchanged in the end and participants of their membership states are synced accordingly.
 *
 * @property groupId ID of group to be modified.
 * @property name New name of modified group.
 * @property participants New participants of modified group.
 * @property notary Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.
 */
@StartableByRPC
class ModifyGroupFlow(
        private val groupId: UniqueIdentifier,
        private val name: String? = null,
        private val participants: Set<UniqueIdentifier>? = null,
        private val notary: Party? = null
) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        return subFlow(ModifyGroupInternalFlow(groupId, name, participants, true, notary))
    }
}

@InitiatingFlow
class ModifyGroupInternalFlow(
        private val groupId: UniqueIdentifier,
        private val name: String? = null,
        private val participants: Set<UniqueIdentifier>? = null,
        private val syncMembershipsParticipants: Boolean = true,
        private val notary: Party? = null
) : MembershipManagementFlow<SignedTransaction>() {

    @Suppress("ComplexMethod")
    @Suspendable
    override fun call(): SignedTransaction {
        auditLogger.info(
                "$ourIdentity started modification of Business Network Group with $groupId group ID " +
                        (if (name != null) "to have new \"$name\" group name" else "") +
                        (if (name != null && participants != null) " and " else "") +
                        if (participants != null) "to have new $participants participants set" else ""
        )

        // validate flow arguments
        if (name == null && participants == null) {
            throw IllegalFlowArgumentException("One of the name or participants arguments must be specified")
        }

        // fetch group state with groupId linear ID and check whether group with groupName already exists
        val bnService = serviceHub.cordaService(BNService::class.java)
        val (group, networkId) = fetchGroupAndValidateName(bnService)

        // check whether party is authorised to initiate flow
        val ourMembership = authorise(networkId, bnService) { it.canModifyGroups() }

        // fetch all participants' memberships and identities
        val (participantsMemberships, participantsIdentities) = fetchParticipantsMembershipsAndIdentities(bnService)

        // validate new group's participants and create output group state if validation is successful
        val (outputGroup, oldParticipantsMemberships) = validateParticipantsModification(networkId, group, participantsIdentities, bnService)

        // execute group modification (transaction building, signing, finalisation and post memberships sync)
        val finalisedTransaction = executeGroupModification(networkId, oldParticipantsMemberships, participantsMemberships, group, outputGroup, ourMembership, bnService)

        if (name != null) {
            auditLogger.info("$ourIdentity successfully modified name of a Business Network Group with $groupId group ID from \"${group.state.data.name}\" to \"$name\"")
        }
        if (participants != null) {
            auditLogger.info("$ourIdentity successfully modified participants of a Business Network Group with $groupId group ID from ${group.state.data.participants} to $participantsIdentities")
        }

        return finalisedTransaction
    }

    /**
     * Fetches group with [groupId] and the network ID it's part of while performing validation of the new proposed [name].
     *
     * @param bnService Service used to query vault for groups.
     *
     * @return Pair of group and the network ID of the Business Network it belongs.
     *
     * @throws DuplicateBusinessNetworkGroupException If Business Network Group with proposed [name] already exists.
     */
    @Suspendable
    private fun fetchGroupAndValidateName(bnService: BNService): Pair<StateAndRef<GroupState>, String> {
        val group = bnService.getBusinessNetworkGroup(groupId)
                ?: throw BusinessNetworkGroupNotFoundException("Business Network group with $groupId linear ID doesn't exist")

        val networkId = group.state.data.networkId
        if (name != null && group.state.data.name != name && bnService.businessNetworkGroupExists(networkId, name)) {
            throw DuplicateBusinessNetworkGroupException("Business Network Group with $name name already exists in Business Network with $networkId ID")
        }

        return group to networkId
    }

    /**
     * Fetches memberships and identities corresponding to [participants] membership IDs.
     *
     * @param bnService Service used to query vault for memberships.
     *
     * @return Pair consisting from list of memberships and list of identities.
     *
     * @throws MembershipNotFoundException If one of the memberships with given linear ID can't be found.
     * @throws IllegalMembershipStatusException If one of the memberships is in pending status.
     */
    @Suspendable
    private fun fetchParticipantsMembershipsAndIdentities(bnService: BNService): Pair<List<StateAndRef<MembershipState>>?, List<Party>?> {
        // get all new participants' memberships from provided membership ids
        val participantsMemberships = participants?.map {
            bnService.getMembership(it)
                    ?: throw MembershipNotFoundException("Cannot find membership with $it linear ID")
        }

        // get all new participants' identities from provided memberships
        val participantsIdentities = participantsMemberships?.map {
            if (it.state.data.isPending()) {
                throw IllegalMembershipStatusException("$it can't be participant of Business Network groups since it has pending status")
            }

            it.state.data.identity.cordaIdentity
        }

        return participantsMemberships to participantsIdentities
    }

    /**
     * Validates proposed participants modification and creates output [GroupState] if the validation is successful.
     *
     * @param networkId ID of the Business Network containing modified group.
     * @param inputGroup [StateAndRef] of the modified group.
     * @param participantsIdentities Identities of all new participants of the modified group.
     * @param bnService Service used to query vault for groups and memberships.
     *
     * @return Pair consisting of output [GroupState] and set of old group participants' memberships.
     *
     * @throws IllegalBusinessNetworkGroupStateException If flow initiator is not part of the new participants list of the group.
     * @throws MembershipNotFoundException If one of the memberships can't be found.
     * @throws MembershipMissingGroupParticipationException If one of the members would remain without any group participation as
     * the result of group modification operation.
     */
    @Suppress("ThrowsCount")
    @Suspendable
    private fun validateParticipantsModification(
            networkId: String,
            inputGroup: StateAndRef<GroupState>,
            participantsIdentities: List<Party>?,
            bnService: BNService
    ): Pair<GroupState, Set<StateAndRef<MembershipState>>> {
        // check if initiator is one of group participants
        if (participantsIdentities != null && !participantsIdentities.contains(ourIdentity)) {
            throw IllegalBusinessNetworkGroupStateException("Initiator must be participant of modified Business Network Group.")
        }

        // check whether any member is not participant of any group
        val outputGroup = inputGroup.state.data.let { groupState ->
            groupState.copy(
                    name = name ?: groupState.name,
                    participants = participantsIdentities ?: groupState.participants,
                    modified = serviceHub.clock.instant()
            )
        }
        val oldParticipantsMemberships = (inputGroup.state.data.participants - outputGroup.participants).map {
            bnService.getMembership(networkId, it)
                    ?: throw MembershipNotFoundException("Cannot find membership with $it linear ID")
        }.toSet()
        val allGroups = bnService.getAllBusinessNetworkGroups(networkId)
        val membersWithoutGroup = oldParticipantsMemberships.filter { membership ->
            membership.state.data.identity.cordaIdentity !in (allGroups - inputGroup).flatMap { it.state.data.participants }
        }
        if (syncMembershipsParticipants && membersWithoutGroup.isNotEmpty()) {
            throw MembershipMissingGroupParticipationException("Illegal group modification: $membersWithoutGroup would remain without any group participation.")
        }

        return outputGroup to oldParticipantsMemberships
    }

    /**
     * Builds group modification transaction, collects all signatures, finalises it and syncs all memberships afterwards.
     *
     * @param networkId ID of the Business Network containing modified group.
     * @param oldParticipantsMemberships List of participants's memberships of the input [GroupState].
     * @param participantsMemberships List of participants's memberships of the output [GroupState].
     * @param inputGroup State and ref of the input [GroupState].
     * @param outputGroup Output [GroupState].
     * @param bnService Service used to query vault for groups and memberships.
     *
     * @return Finalised group modification transaction.
     */
    @Suppress("LongParameterList")
    @Suspendable
    private fun executeGroupModification(
            networkId: String,
            oldParticipantsMemberships: Set<StateAndRef<MembershipState>>,
            participantsMemberships: List<StateAndRef<MembershipState>>?,
            inputGroup: StateAndRef<GroupState>,
            outputGroup: GroupState,
            ourMembership: StateAndRef<MembershipState>,
            bnService: BNService
    ): SignedTransaction {
        // fetch signers
        val authorisedMemberships = bnService.getMembersAuthorisedToModifyMembership(networkId)
        val signers = authorisedMemberships.filter { it.state.data.isActive() }.map { it.state.data.identity.cordaIdentity }

        // building transaction
        val requiredSigners = signers.map { it.owningKey }
        val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(inputGroup)
                .addOutputState(outputGroup)
                .addCommand(GroupContract.Commands.Modify(requiredSigners), requiredSigners)
                .addReferenceState(ourMembership.referenced())
        builder.verify(serviceHub)

        // collect signatures and finalise transaction
        val observers = inputGroup.state.data.participants.toSet() + outputGroup.participants - ourIdentity
        val observerSessions = observers.map { initiateFlow(it) }
        val finalisedTransaction = collectSignaturesAndFinaliseTransaction(builder, observerSessions, signers)

        // sync memberships between all group members
        participantsMemberships?.also { membershipsToSend ->
            sendMemberships(membershipsToSend, observerSessions, observerSessions.filter { it.counterparty in outputGroup.participants }.toHashSet())
        }

        // sync participants of all relevant membership states
        if (syncMembershipsParticipants) {
            syncMembershipsParticipants(
                    networkId,
                    oldParticipantsMemberships + (participantsMemberships ?: emptyList()),
                    signers,
                    bnService,
                    notary
            )
        }

        return finalisedTransaction
    }
}

@InitiatedBy(ModifyGroupInternalFlow::class)
class ModifyGroupResponderFlow(private val session: FlowSession) : MembershipManagementFlow<Unit>() {

    @Suspendable
    override fun call() {
        signAndReceiveFinalisedTransaction(session) {
            if (it.value !is GroupContract.Commands.Modify) {
                throw FlowException("Only Modify command is allowed")
            }
        }
        receiveMemberships(session)
    }
}
