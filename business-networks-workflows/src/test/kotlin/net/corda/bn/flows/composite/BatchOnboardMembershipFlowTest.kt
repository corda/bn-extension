package net.corda.bn.flows.composite

import net.corda.bn.flows.DummyIdentity
import net.corda.bn.flows.MembershipManagementFlowTest
import net.corda.bn.flows.identity
import net.corda.bn.states.GroupState
import org.junit.Test
import java.lang.IllegalStateException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BatchOnboardMembershipFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 3) {

    @Test(timeout = 300_000)
    fun `batch onboard membership flow with all members added to the default group`() {
        val authorisedMember = authorisedMembers.first()

        val networkId = runCreateBusinessNetworkFlow(authorisedMember, businessIdentity = DummyIdentity("dummy-identity")).membershipState().networkId
        val defaultGroupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId

        val onboardedParties = regularMembers.map { OnboardingInfo(it.identity(), DummyIdentity("dummy-identity")) }.toSet()
        runBatchOnboardMembershipFlow(authorisedMember, networkId, onboardedParties, defaultGroupId)

        // check all members' ledgers
        (regularMembers + authorisedMember).forEach { member ->
            val expectedIdentities = (regularMembers + authorisedMember).map { it.identity() }.toSet()

            getAllMembershipsFromVault(member, networkId).apply {
                assertEquals(4, size, "Vault size assertion failed for ${member.identity()}")
                assertTrue(all { it.participants.toSet() == expectedIdentities }, "Membership participants assertion failed for ${member.identity()}'s vault")
                assertTrue(all { it.identity.businessIdentity == DummyIdentity("dummy-identity") }, "Membership Business Identity assertion failed for ${member.identity()}'s vault")
            }
            getAllGroupsFromVault(member, networkId).single().apply {
                assertEquals(expectedIdentities, participants.toSet(), "Group participants assertion failed for ${member.identity()}")
            }
        }
    }

    @Test(timeout = 300_000)
    fun `batch onboard membership flow with members added to specific groups`() {
        val authorisedMember = authorisedMembers.first()

        val networkId = runCreateBusinessNetworkFlow(authorisedMember).membershipState().networkId
        val defaultGroupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId

        val onboardedParties = regularMembers.map {
            val groupId = (runCreateGroupFlow(authorisedMember, networkId).tx.outputStates.single() as GroupState).linearId
            OnboardingInfo(it.identity(), groupId = groupId)
        }.toSet()

        runBatchOnboardMembershipFlow(authorisedMember, networkId, onboardedParties, defaultGroupId)

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

            getAllMembershipsFromVault(member, networkId).apply {
                assertEquals(2, size, "Vault size assertion failed for ${member.identity()}")
                assertEquals(expectedIdentities, map { it.identity.cordaIdentity }.toSet(), "Expected memberships from vault assertion failed for ${member.identity()}")
            }

            getAllGroupsFromVault(member, networkId).single().apply {
                val expectedGroupId = onboardedParties.find { it.party == member.identity() }?.groupId

                assertEquals(expectedGroupId, linearId, "Group ID assertion failed for ${member.identity()}")
                assertEquals(expectedIdentities, participants.toSet(), "Group participants assertion failed for ${member.identity()}")
            }
        }
    }

    @Test(timeout = 300_000)
    fun `batch onboard membership flow with member onboarding subflow fails`() {
        val authorisedMember = authorisedMembers.first()

        val networkId = runCreateBusinessNetworkFlow(authorisedMember, businessIdentity = DummyIdentity("dummy-identity")).membershipState().networkId
        val defaultGroupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId

        // verify flow will not fail if a subflow fails
        val onboardedParties = regularMembers.map { OnboardingInfo(it.identity(), DummyIdentity("dummy-identity")) }.toSet()
        runBatchOnboardMembershipFlow(authorisedMember, networkId, onboardedParties, defaultGroupId, authorisedMember.identity())

        // check authorised member's ledger
        getAllMembershipsFromVault(authorisedMember, networkId).single { it.isActive() }.apply {
            assertEquals(authorisedMember.identity(), identity.cordaIdentity)
        }
        getAllGroupsFromVault(authorisedMember, networkId).single().apply {
            assertEquals(listOf(authorisedMember.identity()), participants)
        }

        // check regular members' ledgers
        regularMembers.forEach { member ->
            assertFailsWith<IllegalStateException> { getAllMembershipsFromVault(member, networkId) }
            assertFailsWith<IllegalStateException> { getAllGroupsFromVault(member, networkId) }
        }
    }
}