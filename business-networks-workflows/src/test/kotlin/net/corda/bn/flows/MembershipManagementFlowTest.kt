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
import net.corda.core.internal.div
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.coretesting.internal.stubs.CertificateStoreStubs
import net.corda.node.services.keys.KeyManagementServiceInternal
import net.corda.nodeapi.internal.DEV_CA_KEY_STORE_PASS
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.storeLegalIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.InternalMockNodeParameters
import net.corda.testing.node.internal.TestCordappImpl
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.startFlow
import org.junit.After
import org.junit.Before
import java.nio.file.Path
import java.security.PublicKey
import kotlin.test.assertNotNull

abstract class MembershipManagementFlowTest(
        private val numberOfAuthorisedMembers: Int,
        private val numberOfRegularMembers: Int
) {

    protected lateinit var authorisedMembers: List<TestStartedNode>
    protected lateinit var regularMembers: List<TestStartedNode>
    private lateinit var mockNetwork: InternalMockNetwork

    @Before
    fun setUp() {
        mockNetwork = InternalMockNetwork(cordappsForAllNodes = listOf(
                TestCordappImpl(scanPackage = "net.corda.bn.contracts", config = emptyMap()),
                TestCordappImpl(scanPackage = "net.corda.bn.flows", config = emptyMap())
        ))

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

    private fun createNode(name: CordaX500Name) = mockNetwork.createNode(InternalMockNodeParameters(legalName = name))

    @Suppress("LongParameterList")
    protected fun runCreateBusinessNetworkFlow(
            initiator: TestStartedNode,
            networkId: UniqueIdentifier = UniqueIdentifier(),
            businessIdentity: BNIdentity? = null,
            groupId: UniqueIdentifier = UniqueIdentifier(),
            groupName: String? = null,
            notary: Party? = null
    ): SignedTransaction {
        val future = initiator.services.startFlow(CreateBusinessNetworkFlow(networkId, businessIdentity, groupId, groupName, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    protected fun runRequestMembershipFlow(
            initiator: TestStartedNode,
            authorisedNode: TestStartedNode,
            networkId: String,
            businessIdentity: BNIdentity? = null,
            notary: Party? = null
    ): SignedTransaction {
        val future = initiator.services.startFlow(RequestMembershipFlow(authorisedNode.identity(), networkId, businessIdentity, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    protected fun runActivateMembershipFlow(initiator: TestStartedNode, membershipId: UniqueIdentifier, notary: Party? = null): SignedTransaction {
        val future = initiator.services.startFlow(ActivateMembershipFlow(membershipId, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    protected fun runRequestAndActivateMembershipFlows(
            initiator: TestStartedNode,
            authorisedNode: TestStartedNode,
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
            initiator: TestStartedNode,
            networkId: String,
            onboardedParty: Party,
            businessIdentity: BNIdentity? = null,
            notary: Party? = null
    ): SignedTransaction {
        val future = initiator.services.startFlow(OnboardMembershipFlow(networkId, onboardedParty, businessIdentity, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow().apply {
            val membership = tx.outputStates.single() as MembershipState
            addMemberToInitialGroup(initiator, networkId, membership, notary)
        }
    }

    protected fun runSuspendMembershipFlow(initiator: TestStartedNode, membershipId: UniqueIdentifier, notary: Party? = null): SignedTransaction {
        val future = initiator.services.startFlow(SuspendMembershipFlow(membershipId, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    protected fun runRequestAndSuspendMembershipFlow(
            initiator: TestStartedNode,
            authorisedNode: TestStartedNode,
            networkId: String,
            businessIdentity: BNIdentity? = null,
            notary: Party? = null
    ): SignedTransaction {
        val membership = runRequestMembershipFlow(initiator, authorisedNode, networkId, businessIdentity, notary).tx.outputStates.single() as MembershipState
        return runSuspendMembershipFlow(authorisedNode, membership.linearId, notary).apply {
            addMemberToInitialGroup(authorisedNode, networkId, membership, notary)
        }
    }

    protected fun runRevokeMembershipFlow(initiator: TestStartedNode, membershipId: UniqueIdentifier, notary: Party? = null): SignedTransaction {
        val future = initiator.services.startFlow(RevokeMembershipFlow(membershipId, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    protected fun runModifyRolesFlow(initiator: TestStartedNode, membershipId: UniqueIdentifier, roles: Set<BNRole>, notary: Party? = null): SignedTransaction {
        val future = initiator.services.startFlow(ModifyRolesFlow(membershipId, roles, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    protected fun runModifyBusinessIdentityFlow(initiator: TestStartedNode, membershipId: UniqueIdentifier, businessIdentity: BNIdentity, notary: Party? = null): SignedTransaction {
        val future = initiator.services.startFlow(ModifyBusinessIdentityFlow(membershipId, businessIdentity, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    @Suppress("LongParameterList")
    protected fun runCreateGroupFlow(
            initiator: TestStartedNode,
            networkId: String,
            groupId: UniqueIdentifier = UniqueIdentifier(),
            groupName: String? = null,
            additionalParticipants: Set<UniqueIdentifier> = emptySet(),
            notary: Party? = null
    ): SignedTransaction {
        val future = initiator.services.startFlow(CreateGroupFlow(networkId, groupId, groupName, additionalParticipants, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    protected fun runModifyGroupFlow(
            initiator: TestStartedNode,
            groupId: UniqueIdentifier,
            name: String? = null,
            participants: Set<UniqueIdentifier>? = null,
            notary: Party? = null
    ): SignedTransaction {
        val future = initiator.services.startFlow(ModifyGroupFlow(groupId, name, participants, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    protected fun runDeleteGroupFlow(initiator: TestStartedNode, groupId: UniqueIdentifier, notary: Party? = null): SignedTransaction {
        val future = initiator.services.startFlow(DeleteGroupFlow(groupId, notary))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    protected fun runBatchActivateMembershipFlow(
            initiator: TestStartedNode,
            memberships: Set<ActivationInfo>,
            defaultGroupId: UniqueIdentifier,
            notary: Party? = null
    ) {
        val future = initiator.services.startFlow(BatchActivateMembershipFlow(memberships, defaultGroupId, notary))
        mockNetwork.runNetwork()
        future.resultFuture.getOrThrow()
    }

    protected fun runBatchOnboardMembershipFlow(
            initiator: TestStartedNode,
            networkId: String,
            onboardedParties: Set<OnboardingInfo>,
            defaultGroupId: UniqueIdentifier,
            notary: Party? = null
    ) {
        val future = initiator.services.startFlow(BatchOnboardMembershipFlow(networkId, onboardedParties, defaultGroupId, notary))
        mockNetwork.runNetwork()
        future.resultFuture.getOrThrow()
    }

    protected fun runBNOAccessControlReportFlow(
            initiator: TestStartedNode,
            networkId: String
    ): AccessControlReport {
        val future = initiator.services.startFlow(BNOAccessControlReportFlow(networkId))
        mockNetwork.runNetwork()
        return future.resultFuture.getOrThrow()
    }

    private fun addMemberToInitialGroup(initiator: TestStartedNode, networkId: String, membership: MembershipState, notary: Party?) {
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

    protected fun getAllMembershipsFromVault(node: TestStartedNode, networkId: String): List<MembershipState> {
        val bnService = node.services.cordaService(BNService::class.java)
        return bnService.getAllMembershipsWithStatus(
                networkId,
                MembershipStatus.PENDING, MembershipStatus.ACTIVE, MembershipStatus.SUSPENDED
        ).map {
            it.state.data
        }
    }

    protected fun getAllGroupsFromVault(node: TestStartedNode, networkId: String): List<GroupState> {
        val bnService = node.services.cordaService(BNService::class.java)
        return bnService.getAllBusinessNetworkGroups(networkId).map { it.state.data }
    }

    protected fun restartNodeWithRotateIdentityKey(node: TestStartedNode): TestStartedNode {
        val oldIdentity = rotateIdentityKey(mockNetwork.baseDirectory(node) / "certificates")
        val restartedNode = mockNetwork.restartNode(node)
        (restartedNode.services.keyManagementService as KeyManagementServiceInternal).start(listOf(oldIdentity))
        return restartedNode
    }

    private fun rotateIdentityKey(certificatesDirectory: Path): Pair<PublicKey, String> {
        val oldIdentityAlias = "old-identity"
        val certStore = CertificateStoreStubs.Signing.withCertificatesDirectory(certificatesDirectory).get()
        certStore.update {
            val oldKey = getPrivateKey(X509Utilities.NODE_IDENTITY_KEY_ALIAS, DEV_CA_KEY_STORE_PASS)
            setPrivateKey(oldIdentityAlias, oldKey, getCertificateChain(X509Utilities.NODE_IDENTITY_KEY_ALIAS), DEV_CA_KEY_STORE_PASS)
        }
        certStore.storeLegalIdentity(X509Utilities.NODE_IDENTITY_KEY_ALIAS)
        return certStore[oldIdentityAlias].publicKey to oldIdentityAlias
    }
}

@CordaSerializable
data class DummyIdentity(val name: String) : BNIdentity

fun TestStartedNode.identity() = info.legalIdentities.single()
