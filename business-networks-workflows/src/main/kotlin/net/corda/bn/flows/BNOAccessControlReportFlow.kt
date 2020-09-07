package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.bn.states.BNRole
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipIdentity
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import java.io.File
import java.time.Instant

@CordaSerializable
data class AccessControlReport(val members: MutableMap<MembershipIdentity, MemberInfos>, val groups: MutableSet<GroupInfos>)

@CordaSerializable
data class MemberInfos(val membershipStatus: MembershipStatus, val groups: MutableSet<String> = mutableSetOf(), val roles: Set<BNRole>)

@CordaSerializable
data class GroupInfos(val name: String?, val participants: List<Party>)

@InitiatingFlow
@StartableByRPC
class BNOAccessControlReportFlow(
        private val networkId: String,
        private val writeToFile: Boolean = false
) : MembershipManagementFlow<AccessControlReport>() {

    @Suspendable
    override fun call(): AccessControlReport {
        val bnService = serviceHub.cordaService(BNService::class.java)

        authorise(networkId, bnService) { it.canActivateMembership() && it.canModifyBusinessIdentity() && it.canModifyGroups() && it.canModifyMembership() && it.canModifyRoles() && it.canRevokeMembership() && it.canSuspendMembership() }

        //list all members in the network with their membership status and roles
        val allMembersOnTheNetwork = getAllMembersWithRolesAndStatus(bnService)

        //collect all groups and their members
        val allGroupsOnTheNetwork = getAllBusinessNetworkGroups(bnService)
        val groupInfos: MutableSet<GroupInfos> = mutableSetOf()
        allGroupsOnTheNetwork.forEach {
            collectTheGroupsForMembers(it, allMembersOnTheNetwork)
            groupInfos.add(GroupInfos(it.state.data.name, it.state.data.participants))
        }

        val reports = AccessControlReport(allMembersOnTheNetwork, groupInfos)

        if(writeToFile) {
            writeToFile(reports)
        }

        logReports(reports, logger::info) {
            logger.info("")
        }

        return reports
    }

    private fun getAllMembersWithRolesAndStatus(bnService: BNService): MutableMap<MembershipIdentity, MemberInfos> {
        val allMembers = mutableMapOf<MembershipIdentity, MemberInfos>()

        bnService.getAllMemberships(networkId).forEach { it ->
            allMembers.put(it.state.data.identity,
                                MemberInfos(membershipStatus = it.state.data.status,
                                roles = it.state.data.roles))
        }
        return allMembers
    }

    private fun getAllBusinessNetworkGroups(bnService: BNService): List<StateAndRef<GroupState>> {
        return bnService.getAllBusinessNetworkGroups(networkId)
    }

    private fun collectTheGroupsForMembers(groupState: StateAndRef<GroupState>, allMembersOnTheNetwork: MutableMap<MembershipIdentity, MemberInfos>) {
        val groupName = groupState.state.data.name
        if(groupName != null){
            allMembersOnTheNetwork.forEach { (key, value) ->
                if(groupState.state.data.participants.contains(key.cordaIdentity)) {
                    value.groups.add(groupName)
                }
            }
        }
    }

    private fun writeToFile(reports: AccessControlReport) {
        val currentTime = Instant.now()
        //find a suitable place for the file
        val fileName = "bno-access-control-report-$currentTime.txt"
        val reportFile = File(fileName)

        reportFile.printWriter().use { out ->
            logReports(reports, out::println, out::println)
        }
    }

    private fun logReports(reports: AccessControlReport, loggerFunction: (String) -> Unit, newLineFunction: () -> Unit) {
        loggerFunction("Access Control report for network $networkId")
        newLineFunction()

        loggerFunction("Participants on the network: ")
        newLineFunction()
        reports.members.forEach { party, infos ->
            loggerFunction("Party identity:")
            loggerFunction("Corda identity: ${party.cordaIdentity}")
            loggerFunction("Business idenity: ${party.businessIdentity}")
            loggerFunction("Membership status: ${infos.membershipStatus}")
            loggerFunction("Groups: ${infos.groups}")
            loggerFunction("Roles:")
            if(infos.roles.isEmpty()) {
                loggerFunction("There are no roles associated with this identity.")
            } else {
                infos.roles.forEach {
                    loggerFunction("Name of the role: ${it.name}")
                    loggerFunction("Permissions: ${it.permissions}")
                }
            }
            newLineFunction()
        }

        loggerFunction("Groups on the network: ")
        newLineFunction()
        reports.groups.forEach {
            loggerFunction("Name of the group: ${it.name}")
            loggerFunction("Participants: ${it.participants}")
            newLineFunction()
        }
    }
}