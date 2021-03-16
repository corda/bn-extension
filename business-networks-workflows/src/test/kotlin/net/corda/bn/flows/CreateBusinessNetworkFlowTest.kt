package net.corda.bn.flows

import net.corda.bn.contracts.GroupContract
import net.corda.bn.contracts.MembershipContract
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.Command
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.TransactionState
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

        val networkId = runCreateBusinessNetworkFlow(authorisedMember).membershipState().networkId
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

        data class TransactionComponents(
            val membership: TransactionState<ContractState>,
            val membershipCommand: Command<*>,
            val group: TransactionState<ContractState>,
            val groupCommand: Command<*>
        )
        val (membership, membershipCommand, group, groupCommand) = runCreateBusinessNetworkFlow(
            authorisedMember,
            businessIdentity = DummyIdentity("dummy-identity")
        ).run {
            verifyRequiredSignatures()
            TransactionComponents(
                tx.outputs.single { it.data is MembershipState },
                tx.commands.single { it.value is MembershipContract.Commands },
                tx.outputs.single { it.data is GroupState },
                tx.commands.single { it.value is GroupContract.Commands }
            )
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
        assertTrue(membershipCommand.value is MembershipContract.Commands.Bootstrap)

        group.run {
            assertEquals(GroupContract.CONTRACT_NAME, contract)
            assertTrue(data is GroupState)
            val data = data as GroupState
            assertEquals(networkId, data.networkId)
            assertEquals(setOf(authorisedMember.identity()), data.participants.toSet())

            data.networkId
        }
        assertTrue(groupCommand.value is GroupContract.Commands.Bootstrap)

        // also check ledger
        getAllMembershipsFromVault(authorisedMember, networkId).single().apply {
            assertEquals(authorisedMember.identity(), identity.cordaIdentity)
        }
        getAllGroupsFromVault(authorisedMember, networkId).single().apply {
            assertEquals(setOf(authorisedMember.identity()), participants.toSet())
        }
    }
}
