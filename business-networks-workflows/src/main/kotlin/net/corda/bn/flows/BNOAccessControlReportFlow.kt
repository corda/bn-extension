package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.contracts.MembershipContract
import net.corda.bn.states.*
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.TransactionBuilder
import java.io.File

@CordaSerializable
data class AccessControlReport(val members: MutableMap<AbstractParty, MemberInfos>)

@CordaSerializable
data class MemberInfos(val membershipStatus: MembershipStatus, val groups: MutableSet<String> = mutableSetOf(), val roles: Set<BNRole>)

@InitiatingFlow
@StartableByRPC
class BNOAccessControlReportFlow(
        private val networkId: UniqueIdentifier = UniqueIdentifier(),
        private val writeToFile: Boolean = false,
        private val notary: Party? = null
) : FlowLogic<AccessControlReport>() {

    @Suspendable
    override fun call(): AccessControlReport {
        val bnService = serviceHub.cordaService(BNService::class.java)
        val ourMembership = bnService.getMembership(networkId.toString(), ourIdentity)

        validate(bnService, ourMembership!!)

        //list all members in the network with their membership status and roles
        val allMembersOnTheNetwork = getAllMembersWithRolesAndStatus(bnService)

        //collect all groups and their members
        val allGroupsOnTheNetwork = getAllBusinessNetworkGroups(bnService)

        allGroupsOnTheNetwork.forEach {
            val groupName = it.state.data.name
            if(groupName != null){
                it.state.data.participants.forEach {
                    if(allMembersOnTheNetwork.containsKey(it)) {
                        allMembersOnTheNetwork.get(it)!!.groups.add(groupName)
                    }
                }
            }
        }

        val reports = AccessControlReport(allMembersOnTheNetwork)

        if(writeToFile) {
            writeToFile(reports)
        }

        //TODO: log the report

        val builder = TransactionBuilder(notary ?: serviceHub.networkMapCache.notaryIdentities.first())
                .addInputState(ourMembership)
                .addCommand(MembershipContract.Commands.AccessControlReport(), ourIdentity.owningKey)

        val stx = serviceHub.signInitialTransaction(builder)
        subFlow(FinalityFlow(stx, emptyList()))

        return reports
    }

    private fun validate(bnService: BNService, ourMembership: StateAndRef<MembershipState>) {
        if(!bnService.businessNetworkExists(networkId.toString())) {
            throw BusinessNetworkNotFoundException("Business Network with $networkId doesn't exist")
        }
    }

    private fun getAllMembersWithRolesAndStatus(bnService: BNService): MutableMap<AbstractParty, MemberInfos> {
        val allMembers = mutableMapOf<AbstractParty, MemberInfos>()

        bnService.getAllMemberships(networkId.toString()).forEach { it ->
            allMembers.put(it.state.data.identity.cordaIdentity,
                                MemberInfos(membershipStatus = it.state.data.status,
                                roles = it.state.data.roles))
        }
        return allMembers
    }

    private fun getAllBusinessNetworkGroups(bnService: BNService): List<StateAndRef<GroupState>> {
        return bnService.getAllBusinessNetworkGroups(networkId.toString())
    }

    private fun writeToFile(reports: AccessControlReport) {
        //TODO: find a suitable place for the file
        val fileName = "myfile.txt"
        val reportFile = File(fileName)

        reportFile.printWriter().use { out ->
            out.println("Access Control report for network $networkId\n")

            reports.members.forEach { party, infos ->
                out.println("Party name: $party")
                out.println("Membership status: ${infos.membershipStatus}")
                out.println("Groups: ${infos.groups}")
                out.println("Roles:")
                infos.roles.forEach {
                    out.println("Name of the role: ${it.name}")
                    out.println("Permissions: ${it.permissions}")
                }
                out.println()
            }
        }
    }
}