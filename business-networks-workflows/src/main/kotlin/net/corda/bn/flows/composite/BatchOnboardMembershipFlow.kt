package net.corda.bn.flows.composite

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.flows.BNService
import net.corda.bn.flows.BusinessNetworkGroupNotFoundException
import net.corda.bn.flows.MembershipNotFoundException
import net.corda.bn.flows.ModifyGroupFlow
import net.corda.bn.flows.OnboardMembershipFlow
import net.corda.bn.states.BNIdentity
import net.corda.bn.states.MembershipState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import java.lang.Exception

/**
 * Contains all the necessary information for batch onboarding.
 *
 * @property party Identity of member to be onboarded.
 * @property businessIdentity Optional Business Identity to be given to onboarded member.
 * @property groupId Optional specific group ID where membership will be added after onboarding. Membership will be
 * added to the default group if the specfic one is not provided.
 */
@CordaSerializable
data class OnboardingInfo(val party: Party, val businessIdentity: BNIdentity? = null, val groupId: UniqueIdentifier? = null)

/**
 * This composite flow is activated by any member authorised to activate membership and modify groups. Onboards
 * provided memberships and adds them to specific or the default group if the specific one is not provided. Failed
 * onboarding requests are be logged and associated members are not added to the group.
 *
 * @property onboardedParties List of parties to be onboarded and group where to be added after onboarding.
 * @property defaultGroupId ID of the group where members are added if the specific group ID is not provided in their
 * [OnboardingInfo].
 * @property notary Identity of the notary to be used for transactions notarisation. If not specified, first one from the whitelist will be used.
 */
@StartableByRPC
class BatchOnboardMembershipFlow(
        private val networkId: String,
        private val onboardedParties: Set<OnboardingInfo>,
        private val defaultGroupId: UniqueIdentifier,
        private val notary: Party? = null
) : FlowLogic<Unit>() {

    @Suppress("TooGenericExceptionCaught")
    @Suspendable
    override fun call() {
        val groups = mutableMapOf<UniqueIdentifier, MutableList<UniqueIdentifier>>()
        onboardedParties.forEach { (party, businessIdentity, groupId) ->
            try {
                val membership = subFlow(OnboardMembershipFlow(networkId, party, businessIdentity, notary)).tx.outputStates.single() as MembershipState
                groups.merge(groupId ?: defaultGroupId, mutableListOf(membership.linearId)) { oldValue, _ ->
                    oldValue.apply { add(membership.linearId) }
                }

                logger.info("Successfully onboarded $party to Business Network with $networkId ID")
            } catch (e: Exception) {
                logger.error("Failed to onboard $party to Business Network with $networkId ID due to $e")
            }
        }

        groups.map { (groupId, participants) ->
            try {
                val bnService = serviceHub.cordaService(BNService::class.java)
                val oldParticipants = bnService.getBusinessNetworkGroup(groupId)?.state?.data?.participants?.map {
                    bnService.getMembership(networkId, it)?.state?.data?.linearId
                            ?: throw MembershipNotFoundException("$it is not member of the Business Network with $networkId ID")
                }
                        ?: throw BusinessNetworkGroupNotFoundException("Business Network Group with $groupId linear ID doesn't exist")

                subFlow(ModifyGroupFlow(groupId, null, oldParticipants.toSet() + participants, notary))

                logger.info("Successfully added onboarded members to group with $groupId linear ID")
            } catch (e: Exception) {
                logger.error("Failed to add onboarded members to group with $groupId linear ID")
            }
        }
    }
}