package net.corda.bn.flows

import net.corda.bn.contracts.ChangeRequestContract
import net.corda.bn.states.AdminPermission
import net.corda.bn.states.BNRole
import net.corda.bn.states.MembershipState
import net.corda.bn.states.ChangeRequestState
import net.corda.bn.states.ChangeRequestStatus
import net.corda.bn.states.BNIdentity
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.serialization.CordaSerializable
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApproveMembershipAttributeChangeFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 2) {

    @CordaSerializable
    private data class DummyIdentity(val name: String) : BNIdentity

    @CordaSerializable
    private class ModifyBusinessIdentityPermission : BNRole("BusinessIdentityPermission", setOf(AdminPermission.CAN_MODIFY_BUSINESS_IDENTITY))

    @CordaSerializable
    private class ModifyRolesPermission : BNRole("RolesPermission", setOf(AdminPermission.CAN_MODIFY_ROLE))

    @Test(timeout = 300_000)
    fun `membership attribute change request approval should fail due to wrong request ID`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId)

        runRequestMembershipAttributeChangeFlow(regularMember, authorisedMember.identity(), networkId, roles = setOf(ModifyBusinessIdentityPermission()))

        assertFailsWith<MembershipChangeRequestNotFoundException> {
            runAcceptMembershipAttributeChangeFlow(authorisedMember, UniqueIdentifier())
        }
    }

    @Test(timeout = 300_000)
    fun `membership attribute change request approval should fail due to authorised member is missing modify role permission`() {
        val authorisedMember = authorisedMembers.first()
        val firstRegularMember = regularMembers.first()
        val secondRegularMember = regularMembers.last()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(firstRegularMember, authorisedMember, networkId)
        val membershipId = (runRequestAndActivateMembershipFlows(secondRegularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState).linearId

        //we add the required role
        runModifyRolesFlow(authorisedMember, membershipId, setOf(ModifyRolesPermission()))

        val request = runRequestMembershipAttributeChangeFlow(firstRegularMember, secondRegularMember.identity(), networkId, roles = setOf(ModifyBusinessIdentityPermission())).run {
            tx.outputs.single()
        }

        //we overwrite the role to make sure we will fail
        runModifyRolesFlow(authorisedMember, membershipId, setOf(ModifyBusinessIdentityPermission()))

        request.apply {
            val data = data as ChangeRequestState
            assertFailsWith<MembershipAuthorisationException> {
                runAcceptMembershipAttributeChangeFlow(secondRegularMember, data.linearId)
            }
        }
    }

    @Test(timeout = 300_000)
    fun `request and approve membership attribute change flow happy path with role change`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId)

        val (request, command) =
                runRequestAndAcceptMembershipAttributeChangeFlow(regularMember, authorisedMember, networkId, roles = setOf(ModifyBusinessIdentityPermission())).run {
                    assertTrue(tx.inputs.size == 1)
                    assertTrue(tx.outputs.size == 1)
                    verifyRequiredSignatures()
                    tx.outputs.single() to tx.commands.single()
                }

        request.apply {
            assertEquals(ChangeRequestContract.CONTRACT_NAME, contract)
            assertTrue(data is ChangeRequestState)
            val data = data as ChangeRequestState
            assertEquals(setOf(ModifyBusinessIdentityPermission()), data.proposedRoleChange)
            assertEquals(ChangeRequestStatus.APPROVED, data.status)
            assertTrue(data.participants.size == 2)
            assertTrue(data.participants.containsAll(listOf(authorisedMember.identity(), regularMember.identity())))
        }
        assertTrue(command.value is ChangeRequestContract.Commands.Approve)
    }

    @Test(timeout = 300_000)
    fun `request and approve membership attribute change flow happy path with role and business identity change`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId)

        val (request, command) =
                runRequestAndAcceptMembershipAttributeChangeFlow(regularMember, authorisedMember, networkId, DummyIdentity("dummy"), setOf(ModifyBusinessIdentityPermission())).run {
                    assertTrue(tx.inputs.size == 1)
                    assertTrue(tx.outputs.size == 1)
                    verifyRequiredSignatures()
                    tx.outputs.single() to tx.commands.single()
                }

        request.apply {
            assertEquals(ChangeRequestContract.CONTRACT_NAME, contract)
            assertTrue(data is ChangeRequestState)
            val data = data as ChangeRequestState
            assertEquals(setOf(ModifyBusinessIdentityPermission()), data.proposedRoleChange)
            assertEquals(DummyIdentity("dummy"), data.proposedBusinessIdentityChange)
            assertEquals(ChangeRequestStatus.APPROVED, data.status)
            assertTrue (data.participants.size == 2)
            assertTrue (data.participants.containsAll(listOf(authorisedMember.identity(), regularMember.identity())))
        }
        assertTrue(command.value is ChangeRequestContract.Commands.Approve)
    }
}