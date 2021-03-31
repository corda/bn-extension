package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.MembershipContract
import net.corda.bn.contracts.GroupContract
import net.corda.bn.states.BNIdentity
import net.corda.bn.states.BNORole
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipIdentity
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/**
 * Self issues [MembershipState] for the flow initiator creating new Business Network as consequence. Every node in Compatibility Zone can
 * initiate this flow. Also creates initial Business Network group in form of [GroupState].
 *
 * @property networkId Custom ID to be given to the new Business Network. If not specified, randomly selected one will be used.
 * @property businessIdentity Custom business identity to be given to membership.
 * @property groupId Custom ID to be given to the initial Business Network group. If not specified, randomly selected one will be used.
 * @property groupName Optional name to be given to Business Network group.
 * @property notary Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.
 *
 * @throws DuplicateBusinessNetworkException If Business Network with [networkId] ID already exists.
 * @throws DuplicateBusinessNetworkGroupException If Business Network Group with [groupId] ID already exists.
 * @throws DuplicateBusinessNetworkRequestException If there is race condition between flow calls which would cause
 * duplicate issuance of Business Network or Business Network Group.
 */
@InitiatingFlow
@StartableByRPC
class CreateBusinessNetworkFlow(
        private val networkId: UniqueIdentifier = UniqueIdentifier(),
        private val businessIdentity: BNIdentity? = null,
        private val groupId: UniqueIdentifier = UniqueIdentifier(),
        private val groupName: String? = null,
        private val notary: Party? = null
) : MembershipManagementFlow<SignedTransaction>() {

    @Suspendable
    override fun call(): SignedTransaction {
        auditLogger.info(
            "$ourIdentity started creation of Business Network with $networkId network ID containing initial Business Network Group with " +
                    "$groupId group ID and $groupName group name"
        )

        val bnService = serviceHub.cordaService(BNService::class.java)

        // check if business network with networkId already exists
        if (bnService.businessNetworkExists(networkId.toString())) {
            throw DuplicateBusinessNetworkException(networkId)
        }

        // check if business network group with groupId already exists
        if (bnService.businessNetworkGroupExists(groupId)) {
            throw DuplicateBusinessNetworkGroupException("Business Network Group with $groupId ID already exists")
        }

        // creating Business Network with ID creation lock so no multiple requests with same data can be made in-flight
        bnService.lockStorage.createLock(BNRequestType.BUSINESS_NETWORK_ID, networkId.toString()) {
            logger.error("Error when trying to create a request for creation of a Business Network with custom network ID")
        }

        // creating Business Network Group with ID creation lock so no multiple requests with same data can be made in-flight
        bnService.lockStorage.createLock(BNRequestType.BUSINESS_NETWORK_GROUP_ID, groupId.toString()) {
            logger.error("Error when trying to create a request for creation of Business Network Group with custom linear ID")
        }

        try {
            val membership = MembershipState(
                identity = MembershipIdentity(ourIdentity, businessIdentity),
                networkId = networkId.toString(),
                status = MembershipStatus.ACTIVE,
                roles = setOf(BNORole()),
                issuer = ourIdentity,
                participants = listOf(ourIdentity)
            )
            val group = GroupState(
                networkId = networkId.toString(),
                name = groupName,
                linearId = groupId,
                participants = listOf(ourIdentity),
                issuer = ourIdentity
            )

            val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addOutputState(membership)
                .addOutputState(group)
                .addCommand(
                    MembershipContract.Commands.Bootstrap(listOf(ourIdentity.owningKey)),
                    ourIdentity.owningKey
                )
                .addCommand(GroupContract.Commands.Bootstrap(listOf(ourIdentity.owningKey)), ourIdentity.owningKey)
            builder.verify(serviceHub)

            val stx = serviceHub.signInitialTransaction(builder)
            val ftx = subFlow(FinalityFlow(stx, emptyList()))

            auditLogger.info("$ourIdentity successfully created Business Network with $networkId network ID")
            auditLogger.info("$ourIdentity successfully created initial Business Network Group with $groupId group ID and $groupName group name")

            return ftx
        } finally {
            // deleting previously created lock since all of the changes are persisted on ledger
            bnService.lockStorage.deleteLock(BNRequestType.BUSINESS_NETWORK_ID, networkId.toString()) {
                logger.warn("Error when trying to delete a request for creation of a Business Network with custom network ID")
            }

            // deleting previously created lock since all of the changes are persisted on ledger
            bnService.lockStorage.deleteLock(BNRequestType.BUSINESS_NETWORK_GROUP_ID, groupId.toString()) {
                logger.warn("Error when trying to delete a request for creation of Business Network Group with custom linear ID")
            }
        }
    }
}
