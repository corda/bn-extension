package net.corda.bn.flows

import net.corda.bn.contracts.GroupContract
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.core.contracts.UniqueIdentifier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModifyGroupFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 1) {

    @Test(timeout = 300_000)
    fun `modify group flow should fail if name and participants argument are not specified`() {
        val authorisedMember = authorisedMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        val groupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId

        assertFailsWith<IllegalFlowArgumentException> { runModifyGroupFlow(authorisedMember, groupId) }
    }

    @Test(timeout = 300_000)
    fun `modify group flow should fail if group with given ID doesn't exist`() {
        val authorisedMember = authorisedMembers.first()

        val illegalGroupId = UniqueIdentifier()
        assertFailsWith<BusinessNetworkGroupNotFoundException> { runModifyGroupFlow(authorisedMember, illegalGroupId, name = "new-name") }
    }

    @Test(timeout = 300_000)
    fun `modify group flow should fail if group with given name already exists`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val groupName = "default-group"
        val (networkId, authorisedMemberId) = (runCreateBusinessNetworkFlow(authorisedMember, groupName = groupName).tx.outputStates.single() as MembershipState).run {
            networkId to linearId
        }
        val regularMemberId = (runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState).linearId
        val defaultGroupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId

        // giving already existing name to another group should fail
        val newGroupId = (runCreateGroupFlow(authorisedMember, networkId, additionalParticipants = setOf(regularMemberId)).tx.outputStates.single() as GroupState).linearId
        assertFailsWith<DuplicateBusinessNetworkGroupException> { runModifyGroupFlow(authorisedMember, newGroupId, name = groupName) }

        // giving already existing name to the group with that name shouldn't fail
        runModifyGroupFlow(authorisedMember, defaultGroupId, groupName, setOf(authorisedMemberId))
    }

    @Test(timeout = 300_000)
    fun `modify group flow should fail if invalid notary argument is provided`() {
        val authorisedMember = authorisedMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        val groupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId

        assertFailsWith<IllegalArgumentException> { runModifyGroupFlow(authorisedMember, groupId, name = "new-name", notary = authorisedMember.identity()) }
    }

    @Test(timeout = 300_000)
    fun `modify group flow should fail if initiator is not part of the business network, its membership is not active or is not authorised`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId

        runRequestAndSuspendMembershipFlow(regularMember, authorisedMember, networkId).apply {
            val membership = tx.outputStates.single() as MembershipState
            val group = runCreateGroupFlow(authorisedMember, networkId, additionalParticipants = setOf(membership.linearId)).tx.outputStates.single() as GroupState
            val groupId = group.linearId

            assertFailsWith<IllegalMembershipStatusException> { runModifyGroupFlow(regularMember, groupId, name = "new-name") }

            runActivateMembershipFlow(authorisedMember, membership.linearId)
            assertFailsWith<MembershipAuthorisationException> { runModifyGroupFlow(regularMember, groupId, name = "new-name") }
        }
    }

    @Test(timeout = 300_000)
    fun `modify group flow should fail if any of the new participants is not member of business network`() {
        val authorisedMember = authorisedMembers.first()

        val membership = runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState
        val groupId = getAllGroupsFromVault(authorisedMember, membership.networkId).single().linearId

        assertFailsWith<MembershipNotFoundException> { runModifyGroupFlow(authorisedMember, groupId, participants = setOf(membership.linearId, UniqueIdentifier())) }
    }

    @Test(timeout = 300_000)
    fun `modify group flow should fail if any of participants membership is in pending status`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val membership = runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState
        val networkId = membership.networkId
        val groupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId
        val pendingMembership = runRequestMembershipFlow(regularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState

        assertFailsWith<IllegalMembershipStatusException> { runModifyGroupFlow(authorisedMember, groupId, participants = setOf(membership.linearId, pendingMembership.linearId)) }
    }

    @Test(timeout = 300_000)
    fun `modify group flow should fail if initiator is not new group participant`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val membership = runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState
        val networkId = membership.networkId
        val groupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId
        val activeMembership = runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState

        assertFailsWith<IllegalBusinessNetworkGroupStateException> { runModifyGroupFlow(authorisedMember, groupId, participants = setOf(activeMembership.linearId)) }
    }

    @Test(timeout = 300_000)
    fun `modify group flow should fail if any member remains without any group participation`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val membership = runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState
        val networkId = membership.networkId
        val groupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState

        assertFailsWith<MembershipMissingGroupParticipationException> { runModifyGroupFlow(authorisedMember, groupId, participants = setOf(membership.linearId)) }
    }

    @Test(timeout = 300_000)
    fun `modify group flow should work after certificate renewal`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val authorisedMembership = runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState
        val networkId = authorisedMembership.networkId
        val regularMembership = runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState

        val group = runCreateGroupFlow(authorisedMember, networkId, additionalParticipants = setOf(regularMembership.linearId)).tx.outputStates.single() as GroupState

        val restartedAuthorisedMember = restartNodeWithRotateIdentityKey(authorisedMember)
        restartNodeWithRotateIdentityKey(regularMember)
        runModifyGroupFlow(restartedAuthorisedMember, group.linearId, "default-group", setOf(authorisedMembership.linearId, regularMembership.linearId))
    }

    @Test(timeout = 300_000)
    fun `modify group flow happy path`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val authorisedMembership = runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState
        val networkId = authorisedMembership.networkId
        val regularMembership = runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState

        val group = runCreateGroupFlow(authorisedMember, networkId).tx.outputStates.single() as GroupState
        val (newGroup, command) = runModifyGroupFlow(authorisedMember, group.linearId, name = "new-name", participants = setOf(authorisedMembership.linearId, regularMembership.linearId)).run {
            assertEquals(1, tx.inputs.size)
            verifyRequiredSignatures()
            tx.outputs.single() to tx.commands.single()
        }

        newGroup.apply {
            assertEquals(GroupContract.CONTRACT_NAME, contract)
            assertTrue(data is GroupState)
            val data = data as GroupState
            assertEquals(networkId, data.networkId)
            assertEquals("new-name", data.name)
            assertEquals(setOf(authorisedMember.identity(), regularMember.identity()), data.participants.toSet())
            assertEquals(group.linearId, data.linearId)
        }
        assertTrue(command.value is GroupContract.Commands.Modify)

        // also check ledgers
        listOf(authorisedMember, regularMember).forEach { member ->
            getAllGroupsFromVault(member, networkId).run {
                assertEquals(2, size)
                single { it.linearId == group.linearId }
            }.apply {
                assertEquals(2, participants.size, "Vault size assertion failed for ${member.identity()}")
                assertTrue(participants.any { it == authorisedMember.identity() }, "Expected to have ${authorisedMember.identity()} in new group of ${member.identity()} vault")
                assertTrue(participants.any { it == regularMember.identity() }, "Expected to have ${regularMember.identity()} in new group of ${member.identity()} vault")
            }
        }
    }
}