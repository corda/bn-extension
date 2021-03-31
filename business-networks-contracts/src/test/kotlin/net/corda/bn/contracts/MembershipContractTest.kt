package net.corda.bn.contracts

import net.corda.bn.states.BNIdentity
import net.corda.bn.states.BNORole
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipIdentity
import net.corda.bn.states.MemberRole
import net.corda.bn.states.MembershipState
import net.corda.bn.states.MembershipStatus
import net.corda.core.contracts.Contract
import net.corda.core.contracts.TypeOnlyCommandData
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import net.corda.testing.common.internal.testNetworkParameters
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import net.corda.testing.node.makeTestIdentityService
import org.junit.Test

class DummyContract : Contract {

    companion object {
        const val CONTRACT_NAME = "net.corda.bn.contracts.DummyContract"
    }

    override fun verify(tx: LedgerTransaction) {}
}

class DummyCommand : TypeOnlyCommandData()

@CordaSerializable
private data class DummyIdentity(val name: String) : BNIdentity

class MembershipContractTest {

    private val ledgerServices = MockServices(
        cordappPackages = listOf("net.corda.bn.contracts"),
        initialIdentityName = CordaX500Name.parse("O=BNO,L=London,C=GB"),
        identityService = makeTestIdentityService(),
        networkParameters = testNetworkParameters(minimumPlatformVersion = 4)
    )

    private val memberIdentity = TestIdentity(CordaX500Name.parse("O=Member,L=London,C=GB")).party
    private val bnoIdentity = TestIdentity(CordaX500Name.parse("O=BNO,L=London,C=GB")).party

    private val membershipState = MembershipState(
            identity = MembershipIdentity(memberIdentity),
            networkId = "network-id",
            status = MembershipStatus.PENDING,
            participants = listOf(memberIdentity, bnoIdentity),
            issuer = bnoIdentity
    )

    private val groupState = GroupState(networkId = "network-id", issuer = bnoIdentity, participants = listOf(bnoIdentity))

    @Test(timeout = 300_000)
    fun `test common contract verification`() {
        ledgerServices.ledger {
            transaction {
                val input = membershipState
                output(MembershipContract.CONTRACT_NAME, input)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), DummyCommand())
                fails()
            }
            transaction {
                val input = membershipState
                input(DummyContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(memberIdentity.owningKey, MembershipContract.Commands.Revoke(listOf(memberIdentity.owningKey)))
                this `fails with` "Input state has to be validated by ${MembershipContract.CONTRACT_NAME}"
            }
            transaction {
                val output = membershipState
                input(MembershipContract.CONTRACT_NAME, output)
                output(DummyContract.CONTRACT_NAME, output)
                command(memberIdentity.owningKey, MembershipContract.Commands.Request(listOf(memberIdentity.owningKey)))
                this `fails with` "Output state has to be validated by ${MembershipContract.CONTRACT_NAME}"
            }
            transaction {
                val input = membershipState.run { copy(modified = modified.minusSeconds(100)) }
                input(MembershipContract.CONTRACT_NAME, input)
                command(memberIdentity.owningKey, MembershipContract.Commands.Revoke(listOf(memberIdentity.owningKey)))
                this `fails with` "Input state's modified timestamp should be greater or equal to issued timestamp"
            }
            transaction {
                val output = membershipState.run { copy(modified = modified.minusSeconds(100)) }
                output(MembershipContract.CONTRACT_NAME, output)
                command(memberIdentity.owningKey, MembershipContract.Commands.Request(listOf(memberIdentity.owningKey)))
                this `fails with` "Output state's modified timestamp should be greater or equal to issued timestamp"
            }
            transaction {
                val output = membershipState.run { copy(participants = listOf(memberIdentity)) }
                output(MembershipContract.CONTRACT_NAME, output)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey)))
                this `fails with` "Required signers should be subset of all output state's participants"
            }

            val input = membershipState
            transaction {
                val output = input.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE)
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership activation transaction should have same Corda identity"
            }
            transaction {
                val output = input.copy(networkId = "other-network-id")
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state should have same network IDs"
            }
            transaction {
                val output = input.run { copy(issuer = memberIdentity) }
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Membership state issuer cannot be changed"
            }
            transaction {
                val output = input.run { copy(issued = issued.minusSeconds(100)) }
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state should have same issued timestamps"
            }
            transaction {
                val output = input.run { copy(modified = modified.plusSeconds(100)) }
                input(MembershipContract.CONTRACT_NAME, input.run { copy(modified = modified.plusSeconds(200)) })
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Output state's modified timestamp should be greater or equal than input's"
            }
            transaction {
                val output = input.copy(linearId = UniqueIdentifier())
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state should have same linear IDs"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Transaction must be signed by all signers specified inside command"
            }

            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Non-bootstrap transaction can't have more than one input"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Non-bootstrap transaction can't have more than one output"
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test bootstrap command contract verification`() {
        ledgerServices.ledger {
            val outputMembership = membershipState.copy(identity = MembershipIdentity(cordaIdentity = bnoIdentity))
            val outputGroup = groupState
            transaction {
                input(MembershipContract.CONTRACT_NAME, outputMembership)
                output(MembershipContract.CONTRACT_NAME, outputMembership)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction shouldn't contain any inputs"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should have 2 outputs"
            }
            transaction {
                output(GroupContract.CONTRACT_NAME, outputGroup)
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should have one output Membership state"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership)
                output(MembershipContract.CONTRACT_NAME, outputMembership)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should have one output Group state"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership)
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should issue membership in ACTIVE status"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership.copy(status = MembershipStatus.ACTIVE))
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should issue membership with single role"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership.copy(status = MembershipStatus.ACTIVE, roles = setOf(BNORole(), MemberRole())))
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should issue membership with single role"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership.copy(status = MembershipStatus.ACTIVE, roles = setOf(MemberRole())))
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should issue membership with BNO role"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership.copy(status = MembershipStatus.ACTIVE, roles = setOf(BNORole()), issuer = memberIdentity))
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should issue membership with issuer field as identity"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership.copy(status = MembershipStatus.ACTIVE, roles = setOf(BNORole()), participants = listOf(bnoIdentity, memberIdentity)))
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should issue membership with no other participants"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership.copy(status = MembershipStatus.ACTIVE, roles = setOf(BNORole()), participants = listOf(bnoIdentity)))
                output(GroupContract.CONTRACT_NAME, outputGroup.copy(networkId = "invalid-network-id"))
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should issue group with same network ID as member"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership.copy(status = MembershipStatus.ACTIVE, roles = setOf(BNORole()), participants = listOf(bnoIdentity)))
                output(GroupContract.CONTRACT_NAME, outputGroup.copy(issuer = memberIdentity))
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should issue group with same issuer as member"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership.copy(status = MembershipStatus.ACTIVE, roles = setOf(BNORole()), participants = listOf(bnoIdentity)))
                output(GroupContract.CONTRACT_NAME, outputGroup.copy(participants = listOf(bnoIdentity, memberIdentity)))
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                this `fails with` "Business Network bootstrap transaction should issue group with issued member as single participant"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, outputMembership.copy(status = MembershipStatus.ACTIVE, roles = setOf(BNORole()), participants = listOf(bnoIdentity)))
                output(GroupContract.CONTRACT_NAME, outputGroup)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                command(listOf(bnoIdentity.owningKey), GroupContract.Commands.Bootstrap(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test request membership command contract verification`() {
        ledgerServices.ledger {
            val output = membershipState
            val bno = membershipState.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE, roles = setOf(BNORole()))
            transaction {
                input(MembershipContract.CONTRACT_NAME, output)
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership request transaction shouldn't contain any inputs"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(status = MembershipStatus.ACTIVE))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership request transaction should contain output state in PENDING status"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(roles = setOf(BNORole())))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership request transaction should issue membership with empty roles set"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey)))
                this `fails with` "Pending membership owner should be required signer of membership request transaction"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                verifies()
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(identity = output.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Request(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test activate membership command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState
            val bno = membershipState.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE, roles = setOf(BNORole()))
            transaction {
                input(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE))
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input state of membership activation transaction shouldn't be already active"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Output state of membership activation transaction should be active"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE, roles = setOf(BNORole())))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership activation transaction should have same roles set"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE, identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership activation transaction should have same business identity"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE, participants = listOf(bnoIdentity)))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership activation transaction should have same participants"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(memberIdentity.owningKey, MembershipContract.Commands.Activate(listOf(memberIdentity.owningKey)))
                this `fails with` "Input membership owner shouldn't be required signer of membership activation transaction"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Activate(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test onboard membership command contract verification`() {
        ledgerServices.ledger {
            val output = membershipState.copy(status = MembershipStatus.ACTIVE)
            val bno = membershipState.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE, roles = setOf(BNORole()))
            transaction {
                input(MembershipContract.CONTRACT_NAME, output)
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership onboarding transaction shouldn't contain any inputs"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(status = MembershipStatus.PENDING))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership onboarding transaction should contain output state in ACTIVE status"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(roles = setOf(BNORole())))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Membership onboarding transaction should issue membership with empty roles set"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey)))
                this `fails with` "Onboarded membership owner should be required signer of membership onboarding transaction"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test suspend membership command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState
            val bno = membershipState.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE, roles = setOf(BNORole()))
            transaction {
                input(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input state of membership suspension transaction shouldn't be already suspended"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.ACTIVE))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                this `fails with` "Output state of membership suspension transaction should be suspended"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED, roles = setOf(BNORole())))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership suspension transaction should have same roles set"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED, identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership suspension transaction should have same business identity"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED, participants = listOf(bnoIdentity)))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership suspension transaction should have same participants"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(memberIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(memberIdentity.owningKey)))
                this `fails with` "Input membership owner shouldn't be required signer of membership suspension transaction"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Suspend(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test revoke membership command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState
            val bno = membershipState.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE, roles = setOf(BNORole()))
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Revoke(listOf(bnoIdentity.owningKey)))
                this `fails with` "Membership revocation transaction shouldn't contain any outputs"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(memberIdentity.owningKey, MembershipContract.Commands.Revoke(listOf(memberIdentity.owningKey)))
                this `fails with` "Input membership owner shouldn't be required signer of membership revocation transaction"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.Revoke(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test modify role command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState.copy(status = MembershipStatus.ACTIVE)
            val bno = membershipState.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE, roles = setOf(BNORole()))
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership roles modification transaction should have same status"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING))
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING, roles = setOf(BNORole())))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey)))
                this `fails with` "Membership roles modification transaction can only be performed on active or suspended state"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership roles modification transaction should have different set of roles"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole()), identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership roles modification transaction should have same business identity"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole()), participants = listOf(bnoIdentity)))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership roles modification transaction should have same participants"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole())))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Input membership owner shouldn't be required signer of membership roles modification transaction if it didn't initiate it"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole())))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyRoles(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test modify business identity command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState.copy(status = MembershipStatus.ACTIVE)
            val bno = membershipState.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE, roles = setOf(BNORole()))
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership business identity modification transaction should have same status"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING))
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING, identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey)))
                this `fails with` "Membership business identity modification transaction can only be performed on active or suspended state"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole())))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership business identity modification transaction should have same roles"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership business identity modification transaction should have different business identity"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity")), participants = listOf(bnoIdentity)))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership business identity modification transaction should have same participants"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Input membership owner shouldn't be required signer of membership business identity modification transaction if it didn't initiate it"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyBusinessIdentity(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `test modify participants command contract verification`() {
        ledgerServices.ledger {
            val input = membershipState.copy(status = MembershipStatus.ACTIVE)
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.SUSPENDED))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership participants modification transaction should have same status"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING))
                output(MembershipContract.CONTRACT_NAME, input.copy(status = MembershipStatus.PENDING))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                this `fails with` "Membership participants modification transaction can only be performed on active or suspended state"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(roles = setOf(BNORole())))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership participants modification transaction should have same roles"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(identity = input.identity.copy(businessIdentity = DummyIdentity("dummy-identity"))))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                this `fails with` "Input and output state of membership participants modification transaction should have same business identity"
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input)
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                verifies()
            }
            transaction {
                input(MembershipContract.CONTRACT_NAME, input)
                output(MembershipContract.CONTRACT_NAME, input.copy(participants = listOf(bnoIdentity)))
                command(bnoIdentity.owningKey, MembershipContract.Commands.ModifyParticipants(listOf(bnoIdentity.owningKey)))
                verifies()
            }
        }
    }

    @Test(timeout = 300_000)
    fun `Test initiator contract verification`() {
        ledgerServices.ledger {
            val output = membershipState.copy(status = MembershipStatus.ACTIVE)
            val bno = membershipState.copy(identity = MembershipIdentity(bnoIdentity), status = MembershipStatus.ACTIVE, roles = setOf(BNORole()))
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                fails()
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(GroupContract.CONTRACT_NAME, groupState)
                reference(GroupContract.CONTRACT_NAME, groupState)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Transaction should not have more than 1 reference state"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(GroupContract.CONTRACT_NAME, groupState)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Transaction should only contain reference MembershipState"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno.copy(networkId = "invalid-network-id"))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Initiator must belong to the same Business Network as the transaction membership"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno.copy(status = MembershipStatus.SUSPENDED))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Initiator must be active member of the Business Network"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno.copy(roles = emptySet()))
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                this `fails with` "Initiator must be authorised to build the membership modification transactions"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output.copy(participants = listOf(memberIdentity)))
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(memberIdentity.owningKey)))
                this `fails with` "Initiator must be one of the participants of the modified state"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(memberIdentity.owningKey)))
                this `fails with` "Initiator must be one of the required signers"
            }
            transaction {
                output(MembershipContract.CONTRACT_NAME, output)
                reference(MembershipContract.CONTRACT_NAME, bno)
                command(listOf(bnoIdentity.owningKey, memberIdentity.owningKey), MembershipContract.Commands.Onboard(listOf(bnoIdentity.owningKey, memberIdentity.owningKey)))
                verifies()
            }
        }
    }
}
