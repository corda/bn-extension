package net.corda.bn.flows

import net.corda.bn.states.MembershipState
import net.corda.bn.states.BNORole
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BNOAccessControlReportFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 2) {

    @Test(timeout = 300_000)
    fun `bno access control flow fails with MembershipAuthorisationException when member is not authorised to run the flow`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = UniqueIdentifier()

        runCreateBusinessNetworkFlow(authorisedMember, networkId = networkId)

        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId.toString())

        assertFailsWith<MembershipAuthorisationException> { runBNOAccessControlReportFlow(regularMember, networkId.toString()) }
    }

    @Test(timeout = 300_000)
    fun `bno access control flow should work after certificate renewal`() {
        val authorisedMember = authorisedMembers.first()
        val firstRegularMember = regularMembers.first()
        val secondRegularMember = regularMembers.last()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId

        listOf(firstRegularMember, secondRegularMember).forEach { member ->
            runRequestAndActivateMembershipFlows(member, authorisedMember, networkId)
        }

        val restartedAuthorisedMember = restartNodeWithRotateIdentityKey(authorisedMember)
        listOf(firstRegularMember, secondRegularMember).forEach { member ->
            restartNodeWithRotateIdentityKey(member)
        }

        runBNOAccessControlReportFlow(restartedAuthorisedMember, networkId)
    }

    @Test(timeout = 300_000)
    fun `bno access control flow happy path`() {
        val authorisedMember = authorisedMembers.first()
        val firstRegularMember = regularMembers.first()
        val secondRegularMember = regularMembers.last()

        val networkId = UniqueIdentifier()

        runCreateBusinessNetworkFlow(authorisedMember, networkId = networkId)

        val membership = runRequestAndActivateMembershipFlows(firstRegularMember, authorisedMember, networkId.toString()).tx.outputStates.single() as MembershipState
        runCreateGroupFlow(authorisedMember, networkId.toString(), UniqueIdentifier(), "my-group", setOf(membership.linearId))

        runRequestMembershipFlow(secondRegularMember, authorisedMember, networkId.toString())

        val accessControlReport = runBNOAccessControlReportFlow(authorisedMember, networkId.toString())

        val infoForAuthorisedMember = accessControlReport.members.filter {
            it.cordaIdentity == authorisedMember.info.legalIdentities[0]
        }.single()

        assertTrue(BNORole() in infoForAuthorisedMember.roles)

        val infoForFirstRegularMember = accessControlReport.members.filter {
            it.cordaIdentity == firstRegularMember.info.legalIdentities[0]
        }.single()

        assertEquals(MembershipStatus.ACTIVE, infoForFirstRegularMember.membershipStatus)
        assertTrue("my-group" in infoForFirstRegularMember.groups)

        val infoForSecondRegularMember = accessControlReport.members.filter {
            it.cordaIdentity == secondRegularMember.info.legalIdentities[0]
        }.single()

        assertEquals(MembershipStatus.PENDING, infoForSecondRegularMember.membershipStatus)

        runSuspendMembershipFlow(authorisedMember, membership.linearId)

        val newAccessControlReport = runBNOAccessControlReportFlow(authorisedMember, networkId.toString())

        val newInfoForFirstRegularMember = newAccessControlReport.members.filter {
            it.cordaIdentity == firstRegularMember.info.legalIdentities[0]
        }.single()

        assertEquals(MembershipStatus.SUSPENDED, newInfoForFirstRegularMember.membershipStatus)
    }
}