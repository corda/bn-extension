package net.corda.bn.flows

import net.corda.bn.flows.composite.ActivationInfo
import net.corda.bn.flows.composite.BatchActivateMembershipFlow
import net.corda.bn.flows.composite.BatchOnboardMembershipFlow
import net.corda.bn.flows.composite.OnboardingInfo
import net.corda.bn.states.BNIdentity
import net.corda.bn.states.BNRole
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNetworkParameters
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.StartedMockNode
import net.corda.testing.node.TestCordapp
import org.junit.After
import org.junit.Before
import kotlin.test.assertNotNull

abstract class MembershipManagementFlowTest(
        private val numberOfAuthorisedMembers: Int,
        private val numberOfRegularMembers: Int
) {

    protected lateinit var authorisedMembers: List<StartedMockNode>
    protected lateinit var regularMembers: List<StartedMockNode>
    private lateinit var mockNetwork: MockNetwork

    @Before
    fun setUp() {
        mockNetwork = MockNetwork(MockNetworkParameters(cordappsForAllNodes = listOf(
                TestCordapp.findCordapp("net.corda.bn.contracts"),
                TestCordapp.findCordapp("net.corda.bn.flows")
        )))

        authorisedMembers = (0 until numberOfAuthorisedMembers).mapIndexed { idx, _ ->
            createNode(CordaX500Name.parse("O=BNO_$idx,L=New York,C=US"))
        }
        regularMembers = (0 until numberOfRegularMembers).mapIndexed { idx, _ ->
            createNode(CordaX500Name.parse("O=Member_$idx,L=New York,C=US"))
        }

        mockNetwork.runNetwork()
    }

    @After
    fun tearDown() {
        mockNetwork.stopNodes()
    }

    private fun createNode(name: CordaX500Name) = mockNetwork.createNode(MockNodeParameters(legalName = name))

    @Suppress("LongParameterList")
    protected fun runCreateBusinessNetworkFlow(
            initiator: StartedMockNode,
            networkId: UniqueIdentifier = UniqueIdentifier(),
            businessIdentity: BNIdentity? = null,
            groupId: UniqueIdentifier = UniqueIdentifier(),
            groupName: String? = null,
            notary: Party? = null
    ): SignedTransaction {
        val future = initiator.startFlow(CreateBusinessNetworkFlow(networkId, businessIdentity, groupId, groupName, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    protected fun runRequestMembershipFlow(
            initiator: StartedMockNode,
            authorisedNode: StartedMockNode,
            networkId: String,
            businessIdentity: BNIdentity? = null,
            notary: Party? = null
    ): SignedTransaction {
        val future = initiator.startFlow(RequestMembershipFlow(authorisedNode.identity(), networkId, businessIdentity, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    protected fun runActivateMembershipFlow(initiator: StartedMockNode, membershipId: UniqueIdentifier, notary: Party? = null): SignedTransaction {
        val future = initiator.startFlow(ActivateMembershipFlow(membershipId, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    protected fun runRequestAndActivateMembershipFlows(
            initiator: StartedMockNode,
            authorisedNode: StartedMockNode,
            networkId: String,
            businessIdentity: BNIdentity? = null,
            notary: Party? = null
    ): SignedTransaction {
        val membership = runRequestMembershipFlow(initiator, authorisedNode, networkId, businessIdentity, notary).tx.outputStates.single() as MembershipState
        return runActivateMembershipFlow(authorisedNode, membership.linearId, notary).apply {
            addMemberToInitialGroup(authorisedNode, networkId, membership, notary)
        }
    }

    protected fun runOnboardMembershipFlow(
            initiator: StartedMockNode,
            networkId: String,
            onboardedParty: Party,
            businessIdentity: BNIdentity? = null,
            notary: Party? = null
    ): SignedTransaction {
        val future = initiator.startFlow(OnboardMembershipFlow(networkId, onboardedParty, businessIdentity, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow().apply {
            val membership = tx.outputStates.single() as MembershipState
            addMemberToInitialGroup(initiator, networkId, membership, notary)
        }
    }

    protected fun runSuspendMembershipFlow(initiator: StartedMockNode, membershipId: UniqueIdentifier, notary: Party? = null): SignedTransaction {
        val future = initiator.startFlow(SuspendMembershipFlow(membershipId, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    protected fun runRequestAndSuspendMembershipFlow(
            initiator: StartedMockNode,
            authorisedNode: StartedMockNode,
            networkId: String,
            businessIdentity: BNIdentity? = null,
            notary: Party? = null
    ): SignedTransaction {
        val membership = runRequestMembershipFlow(initiator, authorisedNode, networkId, businessIdentity, notary).tx.outputStates.single() as MembershipState
        return runSuspendMembershipFlow(authorisedNode, membership.linearId, notary).apply {
            addMemberToInitialGroup(authorisedNode, networkId, membership, notary)
        }
    }

    protected fun runRevokeMembershipFlow(initiator: StartedMockNode, membershipId: UniqueIdentifier, notary: Party? = null): SignedTransaction {
        val future = initiator.startFlow(RevokeMembershipFlow(membershipId, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    protected fun runModifyRolesFlow(initiator: StartedMockNode, membershipId: UniqueIdentifier, roles: Set<BNRole>, notary: Party? = null): SignedTransaction {
        val future = initiator.startFlow(ModifyRolesFlow(membershipId, roles, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    protected fun runModifyBusinessIdentityFlow(initiator: StartedMockNode, membershipId: UniqueIdentifier, businessIdentity: BNIdentity, notary: Party? = null): SignedTransaction {
        val future = initiator.startFlow(ModifyBusinessIdentityFlow(membershipId, businessIdentity, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    @Suppress("LongParameterList")
    protected fun runCreateGroupFlow(
            initiator: StartedMockNode,
            networkId: String,
            groupId: UniqueIdentifier = UniqueIdentifier(),
            groupName: String? = null,
            additionalParticipants: Set<UniqueIdentifier> = emptySet(),
            notary: Party? = null
    ): SignedTransaction {
        val future = initiator.startFlow(CreateGroupFlow(networkId, groupId, groupName, additionalParticipants, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    protected fun runModifyGroupFlow(
            initiator: StartedMockNode,
            groupId: UniqueIdentifier,
            name: String? = null,
            participants: Set<UniqueIdentifier>? = null,
            notary: Party? = null
    ): SignedTransaction {
        val future = initiator.startFlow(ModifyGroupFlow(groupId, name, participants, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    protected fun runDeleteGroupFlow(initiator: StartedMockNode, groupId: UniqueIdentifier, notary: Party? = null): SignedTransaction {
        val future = initiator.startFlow(DeleteGroupFlow(groupId, notary))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    protected fun runBatchActivateMembershipFlow(
            initiator: StartedMockNode,
            memberships: Set<ActivationInfo>,
            defaultGroupId: UniqueIdentifier,
            notary: Party? = null
    ) {
        val future = initiator.startFlow(BatchActivateMembershipFlow(memberships, defaultGroupId, notary))
        mockNetwork.runNetwork()
        future.getOrThrow()
    }

    protected fun runBatchOnboardMembershipFlow(
            initiator: StartedMockNode,
            networkId: String,
            onboardedParties: Set<OnboardingInfo>,
            defaultGroupId: UniqueIdentifier,
            notary: Party? = null
    ) {
        val future = initiator.startFlow(BatchOnboardMembershipFlow(networkId, onboardedParties, defaultGroupId, notary))
        mockNetwork.runNetwork()
        future.getOrThrow()
    }

    protected fun runBNOAccessControlReportFlow(
            initiator: StartedMockNode,
            networkId: String
    ): AccessControlReport {
        val future = initiator.startFlow(BNOAccessControlReportFlow(networkId))
        mockNetwork.runNetwork()
        return future.getOrThrow()
    }

    private fun addMemberToInitialGroup(initiator: StartedMockNode, networkId: String, membership: MembershipState, notary: Party?) {
        val bnService = initiator.services.cordaService(BNService::class.java)
        val group = bnService.getAllBusinessNetworkGroups(networkId).minBy { it.state.data.issued }?.state?.data
        assertNotNull(group)

        val participants = (group!!.participants + membership.identity.cordaIdentity).map {
            val participantMembership = bnService.getMembership(networkId, it)
            assertNotNull(participantMembership)

            participantMembership!!.state.data.linearId
        }
        runModifyGroupFlow(initiator, group.linearId, participants = participants.toSet(), notary = notary)
    }

    protected fun getAllMembershipsFromVault(node: StartedMockNode, networkId: String): List<MembershipState> {
        val bnService = node.services.cordaService(BNService::class.java)
        return bnService.getAllMembershipsWithStatus(
                networkId,
                MembershipStatus.PENDING, MembershipStatus.ACTIVE, MembershipStatus.SUSPENDED
        ).map {
            it.state.data
        }
    }

    protected fun getAllGroupsFromVault(node: StartedMockNode, networkId: String): List<GroupState> {
        val bnService = node.services.cordaService(BNService::class.java)
        return bnService.getAllBusinessNetworkGroups(networkId).map { it.state.data }
    }
}

@CordaSerializable
data class DummyIdentity(val name: String) : BNIdentity

fun StartedMockNode.identity() = info.legalIdentities.single()
