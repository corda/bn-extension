package net.corda.bn.flows

import net.corda.bn.states.AdminPermission
import net.corda.bn.states.BNRole
import net.corda.bn.states.MembershipState
import net.corda.bn.states.ChangeRequestState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.serialization.CordaSerializable
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DeleteMembershipAttributeChangeRequestFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 2) {

    @CordaSerializable
    private class ModifyBusinessIdentityPermission : BNRole("BusinessIdentityPermission", setOf(AdminPermission.CAN_MODIFY_BUSINESS_IDENTITY))

    @Test(timeout = 300_000)
    fun `membership attribute change request removal should fail due to wrong request ID`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId)

        runRequestMembershipAttributeChangeFlow(regularMember, authorisedMember.identity(), networkId, roles = setOf(ModifyBusinessIdentityPermission()))

        assertFailsWith<MembershipChangeRequestNotFoundException> {
            runDeleteMembershipAttributeChangeRequestFlow(authorisedMember, UniqueIdentifier())
        }
    }

    @Test(timeout = 300_000)
    fun `delete membership attribute change request happy path`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId)

        val request =
                runRequestAndDeclineMembershipAttributeChangeFlow(regularMember, authorisedMember, networkId, roles = setOf(ModifyBusinessIdentityPermission())).run {
                    tx.outputs.single()
            }

        request.apply {
            val data = data as ChangeRequestState
            runDeleteMembershipAttributeChangeRequestFlow(authorisedMember, data.linearId).run {
                assertTrue(tx.inputs.size == 1)
                assertTrue(tx.outputs.isEmpty())
                verifyRequiredSignatures()
            }
            assertFalse(hasRequestInVault(regularMember, data.linearId))
        }
    }
}