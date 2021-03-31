package net.corda.bn.flows

import net.corda.bn.states.MembershipState
import net.corda.core.contracts.UniqueIdentifier
import org.junit.Test
import java.lang.IllegalStateException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RevokeMembershipFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 2) {

    @Test(timeout = 300_000)
    fun `revoke membership flow should fail if membership with given ID doesn't exist`() {
        val authorisedMember = authorisedMembers.first()

        val invalidMembershipId = UniqueIdentifier()
        assertFailsWith<MembershipNotFoundException> { runRevokeMembershipFlow(authorisedMember, invalidMembershipId) }
    }

    @Test(timeout = 300_000)
    fun `revoke membership flow should fail if initiator is not part of the business network, its membership is not active or is not authorised`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()
        val nonMember = regularMembers[1]

        val networkId = runCreateBusinessNetworkFlow(authorisedMember).membershipState().networkId
        val membership = runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState

        assertFailsWith<MembershipNotFoundException> { runRevokeMembershipFlow(nonMember, membership.linearId) }

        runRequestAndSuspendMembershipFlow(nonMember, authorisedMember, networkId).apply {
            val initiatorMembership = tx.outputStates.single() as MembershipState

            assertFailsWith<IllegalMembershipStatusException> { runRevokeMembershipFlow(nonMember, membership.linearId) }

            runActivateMembershipFlow(authorisedMember, initiatorMembership.linearId)
            assertFailsWith<MembershipAuthorisationException> { runRevokeMembershipFlow(nonMember, membership.linearId) }
        }
    }

    @Test(timeout = 300_000)
    fun `revoke membership flow should fail if invalid notary argument is provided`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = runCreateBusinessNetworkFlow(authorisedMember).membershipState().networkId

        val membership = runRequestMembershipFlow(regularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState
        assertFailsWith<IllegalArgumentException> { runRevokeMembershipFlow(authorisedMember, membership.linearId, authorisedMember.identity()) }
    }

    @Test(timeout = 300_000)
    fun `revoke membership flow should fail if it results in insufficient admin permissions in the network`() {
        val authorisedMember = authorisedMembers.first()

        val authorisedMembership = runCreateBusinessNetworkFlow(authorisedMember).membershipState()

        assertFailsWith<InvalidBusinessNetworkStateException> { runRevokeMembershipFlow(authorisedMember, authorisedMembership.linearId) }
    }

    @Test(timeout = 300_000)
    fun `revoke membership flow should work after certificate renewal`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val (networkId, authorisedMembershipId) = runCreateBusinessNetworkFlow(authorisedMember).membershipState().run {
            networkId to linearId
        }
        val regularMembership = runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState

        val restartedAuthorisedMember = restartNodeWithRotateIdentityKey(authorisedMember)
        restartNodeWithRotateIdentityKey(regularMember)
        listOf(authorisedMembershipId, regularMembership.linearId).forEach { membershipId ->
            runUpdateCordaIdentityFlow(restartedAuthorisedMember, membershipId)
        }
        runRevokeMembershipFlow(restartedAuthorisedMember, regularMembership.linearId)
    }

    @Test(timeout = 300_000)
    fun `revoke membership flow happy path`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = runCreateBusinessNetworkFlow(authorisedMember).membershipState().networkId
        val membership = runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState

        runRevokeMembershipFlow(authorisedMember, membership.linearId).apply {
            assertEquals(1, tx.inputs.size)
            assertTrue(tx.outputs.isEmpty())
            verifyRequiredSignatures()
        }

        // also check ledgers
        with(authorisedMember) {
            getAllMembershipsFromVault(this, networkId).single().apply {
                assertEquals(authorisedMember.identity(), identity.cordaIdentity)
                assertEquals(setOf(authorisedMember.identity()), participants.toSet())
            }
            getAllGroupsFromVault(this, networkId).single().apply {
                assertEquals(setOf(authorisedMember.identity()), participants.toSet())
            }
        }
        regularMember.also { member ->
            assertFailsWith<IllegalStateException> { getAllMembershipsFromVault(member, networkId).isEmpty() }
            assertFailsWith<IllegalStateException> { getAllGroupsFromVault(member, networkId).isEmpty() }
        }
    }
}
