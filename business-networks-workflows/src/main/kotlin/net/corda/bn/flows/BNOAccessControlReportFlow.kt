package net.corda.bn.flows

import co.paralleluniverse.fibers.Suspendable
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import net.corda.bn.states.BNRole
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipStatus
import net.corda.bn.states.BNORole
import net.corda.bn.states.BNIdentity
import net.corda.client.jackson.JacksonSupport
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import java.io.File
import java.time.Instant

@CordaSerializable
data class AccessControlReport(
    val members: List<AccessControlMember>,
    val groups: Set<GroupInfo>
)

@CordaSerializable
data class AccessControlMember(
        val cordaIdentity: Party,
        val businessIdentity: BNIdentity? = null,
        val membershipStatus: MembershipStatus,
        val groups: Set<String?>,

        @JsonSerialize(using = RoleSerializer::class)
        val roles: Set<BNRole>
)

@CordaSerializable
data class GroupInfo(
        val name: String?,
        val participants: List<Party>
)

/**
 * This flow can be initiated by only authorised members with BNO role.
 * It will create a report which contains data about all the members on the network.
 *
 * @property networkId ID of the Business Network, where the participants are present.
 * @property path The chosen path for the file to be placed.
 *
 * @return [AccessControlReport] containing the information about the members.
 */
@InitiatingFlow
@StartableByRPC
class BNOAccessControlReportFlow(
        private val networkId: String,
        private val path: String? = System.getProperty("user.dir"),
        private val fileName: String? = "bno-access-control-report"
) : MembershipManagementFlow<AccessControlReport>() {

    @Suspendable
    override fun call(): AccessControlReport {
        val bnService = serviceHub.cordaService(BNService::class.java)

        authorise(networkId, bnService) { membership -> membership.roles.any { it is BNORole } }

        // list all members in the network with their membership status and roles
        val allMembersOnTheNetwork = getAllMembersWithRolesAndStatus(bnService)

        // collect all groups and their members
        val groupInfos = getAllBusinessNetworkGroups(bnService).map {
            GroupInfo(it.state.data.name, it.state.data.participants)
        }.toSet()

        // Compare the groups and their members with our participants and update the membership if needed
        val allMembersWithGroups = collectTheGroupsForMembers(
                groupInfos,
                allMembersOnTheNetwork
        )

        val reports = AccessControlReport(allMembersWithGroups, groupInfos)

        writeToFile(reports)

        return reports
    }

    private fun getAllMembersWithRolesAndStatus(bnService: BNService): List<AccessControlMember> {
        return bnService.getAllMemberships(networkId).map {
            val stateData = it.state.data
            AccessControlMember(
                    stateData.identity.cordaIdentity,
                    stateData.identity.businessIdentity,
                    stateData.status,
                    emptySet(),
                    stateData.roles
            )
        }
    }

    private fun getAllBusinessNetworkGroups(bnService: BNService): List<StateAndRef<GroupState>> {
        return bnService.getAllBusinessNetworkGroups(networkId)
    }

    private fun collectTheGroupsForMembers(groupInfos: Set<GroupInfo>,
                                           allMembersOnTheNetwork: List<AccessControlMember>)
            : List<AccessControlMember> {

        return allMembersOnTheNetwork.map { member ->
            val groupsPresent = groupInfos.filter { groupState ->
                val participants = groupState.participants
                participants.contains(member.cordaIdentity)
            }.map {
                it.name
            }

            member.copy(groups = member.groups + groupsPresent)
        }
    }

    private fun writeToFile(reports: AccessControlReport) {
        val fileName = "$path/$fileName-${Instant.now()}"
        logger.info("Writing report file to path ${path}\\")
        val reportFile = File(fileName)

        reportFile.printWriter().use { out ->
            val mapper = JacksonSupport.createNonRpcMapper()
            out.println(mapper.writeValueAsString(reports))
        }
    }
}

class RoleSerializer : JsonSerializer<Set<BNRole>>() {
    override fun serialize(value: Set<BNRole>?, gen: JsonGenerator?, serializers: SerializerProvider?) {
        gen?.writeStartArray()
        value?.forEach {
            gen?.writeStartObject()

            // "name": "ABC"
            gen?.writeFieldName("name")
            gen?.writeString(it.name)

            // "permissions": ["X", "Y", "Z"]
            gen?.writeFieldName("permissions")
            gen?.writeStartArray()
            it.permissions.forEach {
                gen?.writeString(it.toString())
            }
            gen?.writeEndArray()

            gen?.writeEndObject()
        }
        gen?.writeEndArray()
    }
}