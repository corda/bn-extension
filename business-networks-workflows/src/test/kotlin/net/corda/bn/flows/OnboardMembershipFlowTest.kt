package net.corda.bn.flows

import net.corda.bn.contracts.MembershipContract
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.flows.FlowException
import org.junit.Test
import java.lang.IllegalStateException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OnboardMembershipFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 2) {

    @Test(timeout = 300_000)
    fun `onboard membership flow should fail if initiator is not part of the business network, its membership is not active or is not authorised`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()
        val nonMember = regularMembers[1]

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        assertFailsWith<BusinessNetworkNotFoundException> { runOnboardMembershipFlow(nonMember, networkId, regularMember.identity()) }

        runRequestAndSuspendMembershipFlow(nonMember, authorisedMember, networkId).apply {
            val membership = tx.outputStates.single() as MembershipState

            assertFailsWith<IllegalMembershipStatusException> { runOnboardMembershipFlow(nonMember, networkId, regularMember.identity()) }

            runActivateMembershipFlow(authorisedMember, membership.linearId)
            assertFailsWith<MembershipAuthorisationException> { runOnboardMembershipFlow(nonMember, networkId, regularMember.identity()) }
        }
    }

    @Test(timeout = 300_000)
    fun `onboard membership flow should fail if onboarded party is already member of given business network`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runOnboardMembershipFlow(authorisedMember, networkId, regularMember.identity())

        assertFailsWith<FlowException>("is already member of Business Network with $networkId ID") {
            runOnboardMembershipFlow(authorisedMember, networkId, regularMember.identity())
        }
    }

    @Test(timeout = 300_000)
    fun `onboard membership flow should fail if another onboarding request with same data is in progress`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val bnService = authorisedMember.services.cordaService(BNService::class.java)
        bnService.lockStorage.createLock(BNRequestType.PENDING_MEMBERSHIP, regularMember.identity().toString())

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        assertFailsWith<DuplicateBusinessNetworkRequestException> {
            runOnboardMembershipFlow(authorisedMember, networkId, regularMember.identity())
        }

        bnService.lockStorage.deleteLock(BNRequestType.PENDING_MEMBERSHIP, regularMember.identity().toString())
    }

    @Test(timeout = 300_000)
    fun `onboard membership flow should fail if invalid notary argument is provided`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        assertFailsWith<IllegalStateException> {
            runOnboardMembershipFlow(authorisedMember, networkId, regularMember.identity(), notary = authorisedMember.identity())
        }
    }

    @Test(timeout = 300_000)
    fun `onboard membership flow should work after certificate renewal`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId

        val restartedAuthorisedMember = restartNodeWithRotateIdentityKey(authorisedMember)
        runOnboardMembershipFlow(restartedAuthorisedMember, networkId, regularMember.identity())
    }

    @Test(timeout = 300_000)
    fun `onboard membership flow happy path`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId

        val (membership, command) = runOnboardMembershipFlow(authorisedMember, networkId, regularMember.identity(), DummyIdentity("dummy-identity")).run {
            assertTrue(tx.inputs.isEmpty())
            verifyRequiredSignatures()
            tx.outputs.single() to tx.commands.single()
        }

        membership.apply {
            assertEquals(MembershipContract.CONTRACT_NAME, membership.contract)
            assertTrue(data is MembershipState)
            val data = data as MembershipState
            assertEquals(regularMember.identity(), data.identity.cordaIdentity)
            assertEquals(DummyIdentity("dummy-identity"), data.identity.businessIdentity)
            assertEquals(networkId, data.networkId)
            assertEquals(MembershipStatus.ACTIVE, data.status)
        }
        assertTrue(command.value is MembershipContract.Commands.Onboard)

        // also check ledgers
        listOf(authorisedMember, regularMember).forEach { member ->
            getAllMembershipsFromVault(member, networkId).apply {
                assertEquals(2, size, "Vault size assertion failed for ${member.identity()}")
                assertTrue(any { it.identity.cordaIdentity == authorisedMember.identity() }, "Expected to have ${authorisedMember.identity()} in ${member.identity()} vault")
                assertTrue(any { it.identity.cordaIdentity == regularMember.identity() }, "Expected to have ${regularMember.identity()} in ${member.identity()} vault")
            }
        }
    }
}