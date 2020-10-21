package net.corda.bn.flows

import net.corda.bn.contracts.ChangeRequestContract
import net.corda.bn.states.AdminPermission
import net.corda.bn.states.BNRole
import net.corda.bn.states.MembershipState
import net.corda.bn.states.ChangeRequestState
import net.corda.bn.states.ChangeRequestStatus
import net.corda.bn.states.BNIdentity
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.flows.FlowException
import net.corda.core.serialization.CordaSerializable
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequestMembershipAttributeChangeFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 2) {

    @CordaSerializable
    private data class DummyIdentity(val name: String) : BNIdentity

    @CordaSerializable
    private class ModifyBusinessIdentityPermission : BNRole("BusinessIdentityPermission", setOf(AdminPermission.CAN_MODIFY_BUSINESS_IDENTITY))

    @Test(timeout = 300_000)
    fun `request membership attribute change flow should fail if there are no role or business identity changes given`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId)

        assertFailsWith<TransactionVerificationException> {
            runRequestMembershipAttributeChangeFlow(regularMember, authorisedMember.identity(), networkId)
        }
    }

    @Test(timeout = 300_000)
    fun `request membership attribute change flow should fail if business network does not exist`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId)

        assertFailsWith<BusinessNetworkNotFoundException> {
            runRequestMembershipAttributeChangeFlow(regularMember, authorisedMember.identity(), "networkDoesNotExist", roles = setOf(ModifyBusinessIdentityPermission()))
        }
    }

    @Test(timeout = 300_000)
    fun `request membership attribute change flow should fail if another pending membership attribute change request is already in progress`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val bnService = authorisedMember.services.cordaService(BNService::class.java)
        bnService.lockStorage.createLock(BNRequestType.PENDING_ATTRIBUTE_CHANGE_REQUEST, regularMember.identity().toString())

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId)

        assertFailsWith<FlowException> {
            runRequestMembershipAttributeChangeFlow(regularMember, authorisedMember.identity(), networkId, roles = setOf(ModifyBusinessIdentityPermission()))
        }

        bnService.lockStorage.deleteLock(BNRequestType.PENDING_ATTRIBUTE_CHANGE_REQUEST, regularMember.identity().toString())
    }

    @Test(timeout = 300_000)
    fun `request membership attribute change flow should fail if authorised member does not have the required permissions`() {
        val authorisedMember = authorisedMembers.first()
        val firstRegularMember = regularMembers.first()
        val secondRegularMember = regularMembers.last()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(firstRegularMember, authorisedMember, networkId)
        val membershipId = (runRequestAndActivateMembershipFlows(secondRegularMember, authorisedMember, networkId).tx.outputStates.single() as MembershipState).linearId

        runModifyRolesFlow(authorisedMember, membershipId, setOf(ModifyBusinessIdentityPermission()))

        assertFailsWith<MembershipAuthorisationException> {
            runRequestMembershipAttributeChangeFlow(firstRegularMember, secondRegularMember.identity(), networkId, roles = setOf(ModifyBusinessIdentityPermission()))
        }
    }

    @Test(timeout = 300_000)
    fun `request membership attribute change flow happy path with role change`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId)

        val (request, command) =
                runRequestMembershipAttributeChangeFlow(regularMember, authorisedMember.identity(), networkId, roles = setOf(ModifyBusinessIdentityPermission())).run {
                    assertTrue(tx.inputs.isEmpty())
                    assertTrue(tx.outputs.size == 1)
                    verifyRequiredSignatures()
                    tx.outputs.single() to tx.commands.single()
                }

        request.apply {
            assertEquals(ChangeRequestContract.CONTRACT_NAME, contract)
            assertTrue(data is ChangeRequestState)
            val data = data as ChangeRequestState
            assertEquals(setOf(ModifyBusinessIdentityPermission()), data.pendingRoleChange)
            assertEquals(ChangeRequestStatus.PENDING, data.status)
            assertTrue { data.participants.size == 2 }
            assertTrue { data.participants.containsAll(listOf(authorisedMember.identity(), regularMember.identity())) }
            val requestData = getRequestFromVault(regularMember, data.linearId)
            assertEquals(setOf(ModifyBusinessIdentityPermission()), requestData.pendingRoleChange)
        }
        assertTrue(command.value is ChangeRequestContract.Commands.Request)
    }

    @Test(timeout = 300_000)
    fun `request membership attribute change flow happy path with role and business identity change`() {
        val authorisedMember = authorisedMembers.first()
        val regularMember = regularMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        runRequestAndActivateMembershipFlows(regularMember, authorisedMember, networkId)

        val (request, command) =
                runRequestMembershipAttributeChangeFlow(regularMember, authorisedMember.identity(), networkId, DummyIdentity("dummy"), setOf(ModifyBusinessIdentityPermission())).run {
                    assertTrue(tx.inputs.isEmpty())
                    assertTrue(tx.outputs.size == 1)
                    verifyRequiredSignatures()
                    tx.outputs.single() to tx.commands.single()
                }

        request.apply {
            assertEquals(ChangeRequestContract.CONTRACT_NAME, contract)
            assertTrue(data is ChangeRequestState)
            val data = data as ChangeRequestState
            assertEquals(setOf(ModifyBusinessIdentityPermission()), data.pendingRoleChange)
            assertEquals(DummyIdentity("dummy"), data.pendingBusinessIdentityChange)
            assertEquals(ChangeRequestStatus.PENDING, data.status)
            assertTrue { data.participants.size == 2 }
            assertTrue { data.participants.containsAll(listOf(authorisedMember.identity(), regularMember.identity())) }
            val requestData = getRequestFromVault(regularMember, data.linearId)
            assertEquals(setOf(ModifyBusinessIdentityPermission()), requestData.pendingRoleChange)
        }
        assertTrue(command.value is ChangeRequestContract.Commands.Request)
    }
}