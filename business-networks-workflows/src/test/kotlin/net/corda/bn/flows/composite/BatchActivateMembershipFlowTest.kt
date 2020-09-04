package net.corda.bn.flows.composite

import net.corda.bn.flows.MembershipManagementFlowTest
import net.corda.bn.flows.identity
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchActivateMembershipFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 3) {

    @Test(timeout = 300_000)
    fun `batch activate membership flow with all members added to the default group`() {
        val authorisedMember = authorisedMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        val memberships = regularMembers.map {
            (runRequestMembershipFlow(it, authorisedMember, networkId).tx.outputStates.single() as MembershipState).linearId
        }.map {
            ActivationInfo(it)
        }.toSet()

        val defaultGroupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId
        runBatchActivateMembershipFlow(authorisedMember, memberships, defaultGroupId)

        // check all members' ledgers
        (regularMembers + authorisedMember).forEach { member ->
            val expectedIdentities = (regularMembers + authorisedMember).map { it.identity() }.toSet()

            getAllMembershipsFromVault(member, networkId).apply {
                assertEquals(4, size, "Vault size assertion failed for ${member.identity()}")
                assertTrue(all { it.participants.toSet() == expectedIdentities }, "Membership participants assertion failed for ${member.identity()}'s vault")
            }
            getAllGroupsFromVault(member, networkId).single().apply {
                assertEquals(expectedIdentities, participants.toSet(), "Group participants assertion failed for ${member.identity()}")
            }
        }
    }

    @Test(timeout = 300_000)
    fun `batch activate membership flow with members added to specific groups`() {
        val authorisedMember = authorisedMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        val defaultGroupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId

        val memberships = regularMembers.map {
            (runRequestMembershipFlow(it, authorisedMember, networkId).tx.outputStates.single() as MembershipState).linearId
        }.map {
            val groupId = (runCreateGroupFlow(authorisedMember, networkId).tx.outputStates.single() as GroupState).linearId
            ActivationInfo(it, groupId)
        }.toSet()

        runBatchActivateMembershipFlow(authorisedMember, memberships, defaultGroupId)

        // check authorised member's ledger
        val expectedIdentities = (regularMembers + authorisedMember).map { it.identity() }.toSet()
        getAllMembershipsFromVault(authorisedMember, networkId).apply {
            assertEquals(4, size)
            assertEquals(expectedIdentities, map { it.identity.cordaIdentity }.toSet())
        }
        getAllGroupsFromVault(authorisedMember, networkId).apply {
            assertEquals(4, size)
        }

        // check regular members' ledgers
        regularMembers.forEach { member ->
            val expectedIdentities = setOf(authorisedMember.identity(), member.identity())

            val membership = getAllMembershipsFromVault(member, networkId).apply {
                assertEquals(2, size, "Vault size assertion failed for ${member.identity()}")
                assertEquals(expectedIdentities, map { it.identity.cordaIdentity }.toSet(), "Expected memberships from vault assertion failed for ${member.identity()}")
            }.find { it.identity.cordaIdentity == member.identity() }

            getAllGroupsFromVault(member, networkId).single().apply {
                val expectedGroupId = memberships.find { it.membershipId == membership?.linearId }?.groupId

                assertEquals(expectedGroupId, linearId, "Group ID assertion failed for ${member.identity()}")
                assertEquals(expectedIdentities, participants.toSet(), "Group participants assertion failed for ${member.identity()}")
            }
        }
    }

    @Test(timeout = 300_000)
    fun `batch activate membership flow with member activation sublow fails`() {
        val authorisedMember = authorisedMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        val memberships = regularMembers.map {
            (runRequestMembershipFlow(it, authorisedMember, networkId).tx.outputStates.single() as MembershipState).linearId
        }.map {
            ActivationInfo(it)
        }.toSet()

        // verify flow will not fail if a subflow fails
        val defaultGroupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId
        runBatchActivateMembershipFlow(authorisedMember, memberships, defaultGroupId, authorisedMember.identity())

        // check authorised member's ledger
        getAllMembershipsFromVault(authorisedMember, networkId).single { it.isActive() }.apply {
            assertEquals(authorisedMember.identity(), identity.cordaIdentity)
        }
        getAllGroupsFromVault(authorisedMember, networkId).single().apply {
            assertEquals(listOf(authorisedMember.identity()), participants)
        }

        // check regular members' ledgers
        regularMembers.forEach { member ->
            assertTrue(getAllMembershipsFromVault(member, networkId).none { it.isActive() })
            assertTrue(getAllGroupsFromVault(member, networkId).isEmpty())
        }
    }
}