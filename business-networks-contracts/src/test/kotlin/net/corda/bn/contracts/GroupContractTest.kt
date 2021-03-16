package net.corda.bn.contracts

import net.corda.bn.states.BNORole
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipIdentity
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import net.corda.testing.node.makeTestIdentityService
import org.junit.Test

class GroupContractTest {

    private val ledgerServices = MockServices(
        cordappPackages = listOf("net.corda.bn.contracts"),
        initialIdentityName = CordaX500Name.parse("O=BNO,L=London,C=GB"),
        identityService = makeTestIdentityService(),
        networkParameters = testNetworkParameters(minimumPlatformVersion = 4)
    )

    private val memberIdentity = TestIdentity(CordaX500Name.parse("O=Member,L=London,C=GB")).party
    private val bnoIdentity = TestIdentity(CordaX500Name.parse("O=BNO,L=London,C=GB")).party

    private val membershipState = MembershipState(
        identity = MembershipIdentity(bnoIdentity),
        networkId = "network-id",
        status = MembershipStatus.ACTIVE,
        roles = setOf(BNORole()),
        participants = listOf(bnoIdentity),
        issuer = bnoIdentity
    )

    private val groupState = GroupState(networkId = "network-id", participants = listOf(memberIdentity, bnoIdentity), issuer = bnoIdentity)

    @Test(timeout = 300_000)
    fun `test common contract verification`() {
        ledgerServices.ledger {
            transaction {
                val input = groupState
                output(GroupContract.CONTRACT_NAME, input)
                command(bnoIdentity.owningKey, DummyCommand())
                fails()
            }
            transaction {
                val input = groupState
                input(DummyContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, input)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input state has to be validated by ${GroupContract.CONTRACT_NAME}"
            }
            transaction {
                val input = groupState
                val output = input.copy(issuer = memberIdentity)
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, output)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                this `fails with` "Group state issuer cannot be changed"
            }
            transaction {
                val input = groupState
                input(GroupContract.CONTRACT_NAME, input)
                output(DummyContract.CONTRACT_NAME, input)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                this `fails with` "Output state has to be validated by ${GroupContract.CONTRACT_NAME}"
            }
            transaction {
                val input = groupState.run { copy(modified = issued.minusSeconds(100)) }
                input(GroupContract.CONTRACT_NAME, input)
                command(bnoIdentity.owningKey, GroupContract.Commands.Exit(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input state's modified timestamp should be greater or equal to issued timestamp"
            }
            transaction {
                val output = groupState.run { copy(modified = issued.minusSeconds(100)) }
                output(GroupContract.CONTRACT_NAME, output)
                command(bnoIdentity.owningKey, GroupContract.Commands.Create(listOf(bnoIdentity.owningKey)))
                this `fails with` "Output state's modified timestamp should be greater or equal to issued timestamp"
            }

            val input = groupState
            transaction {
                val output = input.copy(networkId = "other-network-id")
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, output)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state should have same network IDs"
            }
            transaction {
                val output = input.run { copy(issued = issued.minusSeconds(100)) }
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, output)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state should have same issued timestamps"
            }
            transaction {
                val output = input.run { copy(modified = modified.plusSeconds(100)) }
                input(GroupContract.CONTRACT_NAME, input.run { copy(modified = modified.plusSeconds(200)) })
                output(GroupContract.CONTRACT_NAME, output)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                this `fails with` "Output state's modified timestamp should be greater or equal than input's"
            }
            transaction {
                val output = input.copy(linearId = UniqueIdentifier())
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, output)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state should have same linear IDs"
            }
            transaction {
                val output = input.copy(name = "new-name")
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, output)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(emptyList()))
                this `fails with` "Transaction must be signed by all signers specified inside command"
            }

            transaction {
                input(GroupContract.CONTRACT_NAME, input)
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, input)
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Non-bootstrap transaction can't have more than one input"
            }
            transaction {
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, input)
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Non-bootstrap transaction can't have more than one output"
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test boostrap command contract verification`() {
        ledgerServices.ledger {
            val outputMembership = membershipState
            val outputGroup = groupState.copy(participants = listOf(bnoIdentity))
            transaction {
                input(GroupContract.CONTRACT_NAME, outputGroup)
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(bnoIdentity.owningKey, GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction shouldn't contain any inputs"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(bnoIdentity.owningKey, GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should have 2 outputs"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, outputGroup)
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(bnoIdentity.owningKey, GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should have one output Membership state"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership)
                output(MembershipContract.CONTRACT_NAME, outputMembership)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should have one output Group state"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership)
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(bnoIdentity.owningKey, GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test create group command contract verification`() {
        ledgerServices.ledger {
            val output = groupState
            val bno = membershipState
            transaction {
                input(GroupContract.CONTRACT_NAME, output)
                output(GroupContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, GroupContract.Commands.Create(listOf(bnoIdentity.owningKey)))
                this `fails with` "Group issuance transaction shouldn't contain any inputs"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, GroupContract.Commands.Create(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test modify group command contract verification`() {
        ledgerServices.ledger {
            val input = groupState
            val bno = membershipState
            transaction {
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, input)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output states of group modification transaction should have different name or participants field"
            }
            transaction {
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, input.copy(name = "new-name"))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                verifies()
            }
            transaction {
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, input.copy(participants = listOf(bnoIdentity)))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                verifies()
            }
            transaction {
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, input.copy(name = "new-name", participants = listOf(bnoIdentity)))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, GroupContract.Commands.Modify(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test delete group command contract verification`() {
        ledgerServices.ledger {
            val input = groupState
            val bno = membershipState
            transaction {
                input(GroupContract.CONTRACT_NAME, input)
                output(GroupContract.CONTRACT_NAME, input)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, GroupContract.Commands.Exit(listOf(bnoIdentity.owningKey)))
                this `fails with` "Group exit transaction shouldn't contain any outputs"
            }
            transaction {
                input(GroupContract.CONTRACT_NAME, input)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, GroupContract.Commands.Exit(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `Test initiator contract verification`() {
        ledgerServices.ledger {
            val output = groupState
            val bno = membershipState.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE, roles = setOf(BNORole()))
            transaction {
                output(GroupContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), GroupContract.Commands.Create(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                fails()
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, output)
                reference(GroupContract.CONTRACT_NAME, groupState)
                reference(GroupContract.CONTRACT_NAME, groupState)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), GroupContract.Commands.Create(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Transaction should not have more than 1 reference state"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, output)
                reference(GroupContract.CONTRACT_NAME, groupState)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), GroupContract.Commands.Create(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Transaction should only contain reference MembershipState"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno.copy(networkId = "invalid-network-id"))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), GroupContract.Commands.Create(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Initiator must belong to the same Business Network as the transaction membership"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno.copy(status = MembershipStatus.SUSPENDED))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), GroupContract.Commands.Create(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Initiator must be active member of the Business Network"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno.copy(roles = emptySet()))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), GroupContract.Commands.Create(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Initiator must be authorised to build the membership modification transactions"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, output.copy(participants = listOf(memberIdentity)))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(memberIdentity.owningKey), GroupContract.Commands.Create(listOf(memberIdentity.owningKey)))
                this `fails with` "Initiator must be one of the participants of the modified state"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(memberIdentity.owningKey), GroupContract.Commands.Create(listOf(memberIdentity.owningKey)))
                this `fails with` "Initiator must be one of the required signers"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), GroupContract.Commands.Create(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                verifies()
            }
        }
    }
}