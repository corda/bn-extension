package net.corda.bn.flows.composite

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.flows.ActivateMembershipFlow
import net.corda.bn.flows.BNService
import net.corda.bn.flows.BusinessNetworkGroupNotFoundException
import net.corda.bn.flows.MembershipManagementFlow
import net.corda.bn.flows.MembershipNotFoundException
import net.corda.bn.flows.ModifyGroupFlow
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import java.lang.Exception

/**
 * Contains all the necessary information for batch activation.
 *
 * @property membershipId ID of the membership to be activated.
 * @property groupId Optional specific group ID where membership will be added after activation. Membership will be
 * added to the default group if the specific one is not provided.
 */
@CordaSerializable
data class ActivationInfo(val membershipId: UniqueIdentifier, val groupId: UniqueIdentifier? = null)

/**
 * This composite flow is activated by any member authorised to activate membership and modify groups. Activates
 * provided memberships and adds them to specific or the default group if the specific one is not provided. Failed
 * activations are logged and associated members are not added to the group.
 *
 * @property memberships Set of memberships' [ActivationInfo]s.
 * @property defaultGroupId ID of the group where members are added if the specific group ID is not provided in their
 * [ActivationInfo].
 * @property notary Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.
 */
@StartableByRPC
class BatchActivateMembershipFlow(
        private val memberships: Set<ActivationInfo>,
        private val defaultGroupId: UniqueIdentifier,
        private val notary: Party? = null
) : MembershipManagementFlow<Unit>() {

    @Suppress("TooGenericExceptionCaught")
    @Suspendable
    override fun call() {
        auditLogger.info("$ourIdentity started batch activation of $memberships memberships with $defaultGroupId default placement group")

        val groups = mutableMapOf<UniqueIdentifier, MutableList<UniqueIdentifier>>()
        memberships.forEach { (membershipId, groupId) ->
            try {
                subFlow(ActivateMembershipFlow(membershipId, notary))
                groups.merge(groupId ?: defaultGroupId, mutableListOf(membershipId)) { oldValue, _ ->
                    oldValue.apply { add(membershipId) }
                }

                auditLogger.info("$ourIdentity successfully activated membership with $membershipId linear ID")
                logger.info("Successfully activated membership with $membershipId linear ID")
            } catch (e: Exception) {
                logger.error("Failed to activate membership with $membershipId linear ID")
            }
        }

        groups.map { (groupId, participants) ->
            try {
                val bnService = serviceHub.cordaService(BNService::class.java)
                val group = bnService.getBusinessNetworkGroup(groupId)?.state?.data
                        ?: throw BusinessNetworkGroupNotFoundException("Business Network Group with $groupId linear ID doesn't exist")

                val oldParticipants = group.participants.map {
                    val networkId = group.networkId
                    bnService.getMembership(networkId, it)?.state?.data?.linearId
                            ?: throw MembershipNotFoundException("$it is not member of the Business Network with $networkId ID")
                }

                subFlow(ModifyGroupFlow(groupId, null, oldParticipants.toSet() + participants, notary))

                auditLogger.info("$ourIdentity successfully added activated members to group with $groupId linear ID")
                logger.info("Successfully added activated members to group with $groupId linear ID")
            } catch (e: Exception) {
                logger.error("Failed to add activated members to group with $groupId linear ID")
            }
        }
    }
}
