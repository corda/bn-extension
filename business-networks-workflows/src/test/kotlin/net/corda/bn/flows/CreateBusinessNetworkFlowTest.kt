package net.corda.bn.flows

import net.corda.bn.contracts.MembershipContract
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CreateBusinessNetworkFlowTest : MembershipManagementFlowTest(numberOfAuthorisedMembers = 1, numberOfRegularMembers = 0) {

    @Test(timeout = 300_000)
    fun `create business network flow should fail when trying to create business network with already existing network ID`() {
        val authorisedMember = authorisedMembers.first()
        val networkId = UniqueIdentifier()

        runCreateBusinessNetworkFlow(authorisedMember, networkId = networkId)
        val e = assertFailsWith<DuplicateBusinessNetworkException> { runCreateBusinessNetworkFlow(authorisedMember, networkId = networkId) }
        assertEquals(networkId, e.networkId)
    }

    @Test(timeout = 300_000)
    fun `create business network flow should fail if another request for the custom network ID is already in progress`() {
        val authorisedMember = authorisedMembers.first()
        val networkId = UniqueIdentifier()

        val bnService = authorisedMember.services.cordaService(BNService::class.java)
        bnService.lockStorage.createLock(BNRequestType.BUSINESS_NETWORK_ID, networkId.toString())

        val e = assertFailsWith<DuplicateBusinessNetworkRequestException> { runCreateBusinessNetworkFlow(authorisedMember, networkId = networkId) }
        assertEquals(BNRequestType.BUSINESS_NETWORK_ID, e.type)
        assertEquals(networkId.toString(), e.data)

        bnService.lockStorage.deleteLock(BNRequestType.BUSINESS_NETWORK_ID, networkId.toString())
    }

    @Test(timeout = 300_000)
    fun `create business network flow should fail when invalid notary argument is provided`() {
        val authorisedMember = authorisedMembers.first()

        assertFailsWith<IllegalStateException> { runCreateBusinessNetworkFlow(authorisedMember, notary = authorisedMember.identity()) }
    }

    @Test(timeout = 300_000)
    fun `create business network flow should fail when trying to create initial group with already existing group ID`() {
        val authorisedMember = authorisedMembers.first()

        val networkId = (runCreateBusinessNetworkFlow(authorisedMember).tx.outputStates.single() as MembershipState).networkId
        val groupId = getAllGroupsFromVault(authorisedMember, networkId).single().linearId

        assertFailsWith<DuplicateBusinessNetworkGroupException> { runCreateBusinessNetworkFlow(authorisedMember, groupId = groupId) }
    }

    @Test(timeout = 300_000)
    fun `create business network flow should fail if another request for the custom group ID is already in progress`() {
        val authorisedMember = authorisedMembers.first()
        val groupId = UniqueIdentifier()

        val bnService = authorisedMember.services.cordaService(BNService::class.java)
        bnService.lockStorage.createLock(BNRequestType.BUSINESS_NETWORK_GROUP_ID, groupId.toString())

        val e = assertFailsWith<DuplicateBusinessNetworkRequestException> { runCreateBusinessNetworkFlow(authorisedMember, groupId = groupId) }
        assertEquals(BNRequestType.BUSINESS_NETWORK_GROUP_ID, e.type)
        assertEquals(groupId.toString(), e.data)

        bnService.lockStorage.deleteLock(BNRequestType.BUSINESS_NETWORK_GROUP_ID, groupId.toString())
    }

    @Test(timeout = 300_000)
    fun `create business network flow should work after certificate renewal`() {
        val authorisedMember = authorisedMembers.first()
        runCreateBusinessNetworkFlow(authorisedMember)

        val restartedAuthorisedMember = restartNodeWithRotateIdentityKey(authorisedMember)
        runCreateBusinessNetworkFlow(restartedAuthorisedMember)
    }

    @Test(timeout = 300_000)
    fun `create business network flow happy path`() {
        val authorisedMember = authorisedMembers.first()
        val (membership, command) = runCreateBusinessNetworkFlow(authorisedMember, businessIdentity = DummyIdentity("dummy-identity")).run {
            verifyRequiredSignatures()
            tx.outputs.single() to tx.commands.single()
        }

        val networkId = membership.run {
            assertEquals(MembershipContract.CONTRACT_NAME, contract)
            assertTrue(data is MembershipState)
            val data = data as MembershipState
            assertEquals(authorisedMember.identity(), data.identity.cordaIdentity)
            assertEquals(DummyIdentity("dummy-identity"), data.identity.businessIdentity)
            assertEquals(MembershipStatus.ACTIVE, data.status)

            data.networkId
        }
        assertTrue(command.value is MembershipContract.Commands.ModifyRoles)

        // also check ledger
        getAllMembershipsFromVault(authorisedMember, networkId).single().apply {
            assertEquals(authorisedMember.identity(), identity.cordaIdentity)
        }
        getAllGroupsFromVault(authorisedMember, networkId).single().apply {
            assertEquals(setOf(authorisedMember.identity()), participants.toSet())
        }
    }
}
