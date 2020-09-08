package net.corda.bn.flows

import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipIdentity
import net.corda.bn.states.BNORole
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BNOAccessControlReportFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 2) {

    @Test(timeout = 300_000)
    fun `bno access control flow fails with MembershipAuthorisationException`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = UniqueIdentifier()

        runCreateBusinessNetworkFlow(authorisedMember, networkId = networkId)

        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId.toString()).run {
            assertEquals(1, tx.inputs.size)
            verifyRequiredSignatures()
            tx.outputs.single() to tx.commands.single()
        }

        assertFailsWith<MembershipAuthorisationException> { runBNOAccessControlReportFlow(regularMember, networkId.toString()) }
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

        val infoForAuthorisedMember = accessControlReport.members.get(MembershipIdentity(authorisedMember.info.legalIdentities.get(0), null))
        assertTrue { infoForAuthorisedMember!!.roles.contains(BNORole()) }

        val infoForFirstRegularMember = accessControlReport.members.get(MembershipIdentity(firstRegularMember.info.legalIdentities.get(0), null))
        assertEquals(MembershipStatus.ACTIVE, infoForFirstRegularMember!!.membershipStatus)
        assertTrue(infoForFirstRegularMember.groups.contains("my-group"))

        val infoForSecondRegularMember = accessControlReport.members.get(MembershipIdentity(secondRegularMember.info.legalIdentities.get(0), null))
        assertEquals(MembershipStatus.PENDING, infoForSecondRegularMember!!.membershipStatus)

        runSuspendMembershipFlow(authorisedMember, membership.linearId)

        val newAccessControlReport = runBNOAccessControlReportFlow(authorisedMember, networkId.toString())

        val newInfoForFirstRegularMember = newAccessControlReport.members.get(MembershipIdentity(firstRegularMember.info.legalIdentities.get(0), null))
        assertEquals(MembershipStatus.SUSPENDED, newInfoForFirstRegularMember!!.membershipStatus)
    }
}