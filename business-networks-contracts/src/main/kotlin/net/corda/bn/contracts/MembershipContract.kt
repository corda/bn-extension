package net.corda.bn.contracts

import net.corda.bn.states.BNORole
import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.CommandWithParties
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.lang.IllegalArgumentException
import java.security.PublicKey

/**
 * Contract that verifies an evolution of [MembershipState].
 */
open class MembershipContract : BNContract {

    companion object {
        const val CONTRACT_NAME = "net.corda.bn.contracts.MembershipContract"
    }

    /**
     * Each new [MembershipContract] command must be wrapped and extend this class.
     *
     * @property requiredSigners List of all required public keys of command's signers.
     */
    open class Commands(val requiredSigners: List<PublicKey>) : CommandData {

        /**
         * Command responsible for Business Network creation transaction.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Bootstrap(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for pending [MembershipState] issuance.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Request(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for [MembershipState] activation.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Activate(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for direct issuance of active [MembershipState] without need to do the request. The
         * transaction containing this command is meant to be created by a member authorised to activate memberships.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Onboard(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for [MembershipState] suspension.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Suspend(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for [MembershipState] revocation.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Revoke(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for modification of [MembershipState.roles].
         *
         * @param requiredSigners List of all required public keys of command's signers.
         * @property initiator Identity of the party building the transaction.
         */
        class ModifyRoles(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for the update of Corda identity (public key) of members affected by a certificate or key
         * rotation.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class ModifyCordaIdentity(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for modification of [MembershipState.identity.businessIdentity].
         *
         * @param requiredSigners List of all required public keys of command's signers.
         * @property initiator Identity of the party building the transaction.
         */
        class ModifyBusinessIdentity(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for modification of [MembershipState.participants].
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class ModifyParticipants(requiredSigners: List<PublicKey>) : Commands(requiredSigners)
    }

    /**
     * Ensures [MembershipState] transition makes sense. Throws exception if there is a problem that should prevent the transition.
     *
     * @param tx Ledger transaction over which contract performs verification.
     */
    @Suppress("ComplexMethod")
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val input = if (tx.inputStates.isNotEmpty()) tx.inputs.singleOrNull { it.state.data is MembershipState } else null
        val inputState = input?.state?.data as? MembershipState
        val output = if (tx.outputStates.isNotEmpty()) tx.outputs.singleOrNull { it.data is MembershipState } else null
        val outputState = output?.data as? MembershipState

        requireThat {
            "Non-bootstrap transaction can't have more than one input" using (tx.inputs.size <= 1 || command.value is Commands.Bootstrap)
            "Non-bootstrap transaction can't have more than one output" using (tx.outputs.size <= 1 || command.value is Commands.Bootstrap)
            "Transaction should not have more than 1 reference state" using (tx.referenceStates.size <= 1)
            "Transaction should only contain reference MembershipState" using (tx.referenceStates.all { it is MembershipState })

            input?.apply {
                "Input state has to be validated by ${contractName()}" using (state.contract == contractName())
            }
            inputState?.apply {
                "Input state's modified timestamp should be greater or equal to issued timestamp" using (issued <= modified)
            }
            output?.apply {
                "Output state has to be validated by ${contractName()}" using (contract == contractName())
            }
            outputState?.apply {
                "Output state's modified timestamp should be greater or equal to issued timestamp" using (issued <= modified)
                "Required signers should be subset of all output state's participants" using (participants.map { it.owningKey }
                    .containsAll(command.value.requiredSigners))
            }

            if (inputState != null && outputState != null) {
                "Input and output state should have same network IDs" using (inputState.networkId == outputState.networkId)
                "Input and output state should have same issued timestamps" using (inputState.issued == outputState.issued)
                "Membership state issuer cannot be changed" using (inputState.issuer == outputState.issuer)
                "Output state's modified timestamp should be greater or equal than input's" using (inputState.modified <= outputState.modified)
                "Input and output state should have same linear IDs" using (inputState.linearId == outputState.linearId)
                "Transaction must be signed by all signers specified inside command" using (command.signers.toSet() == command.value.requiredSigners.toSet())
            }
        }

        when (command.value) {
            is Commands.Bootstrap -> verifyBootstrap(tx, command)
            is Commands.Request -> verifyRequest(tx, command, outputState!!)
            is Commands.Activate -> verifyActivate(tx, command, inputState!!, outputState!!)
            is Commands.Onboard -> verifyOnboard(tx, command, outputState!!)
            is Commands.Suspend -> verifySuspend(tx, command, inputState!!, outputState!!)
            is Commands.Revoke -> verifyRevoke(tx, command, inputState!!)
            is Commands.ModifyRoles -> verifyModifyRoles(tx, command, inputState!!, outputState!!)
            is Commands.ModifyCordaIdentity -> verifyModifyCordaIdentity(tx, command, inputState!!, outputState!!)
            is Commands.ModifyBusinessIdentity -> verifyModifyBusinessIdentity(tx, command, inputState!!, outputState!!)
            is Commands.ModifyParticipants -> verifyModifyParticipants(tx, command, inputState!!, outputState!!)
            else -> throw IllegalArgumentException("Unsupported command ${command.value}")
        }
    }

    /**
     * Each contract extending [MembershipContract] must override this method providing associated contract name.
     */
    open fun contractName() = CONTRACT_NAME

    /**
     * Contract verification check specific to [Commands.Bootstrap] command. Each contract extending [MembershipContract] can override this
     * method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about membership creation command.
     */
    open fun verifyBootstrap(tx: LedgerTransaction, command: CommandWithParties<Commands>) = requireThat {
        "Business Network bootstrap transaction shouldn't contain any inputs" using (tx.inputs.isEmpty())
        "Business Network bootstrap transaction should have 2 outputs" using (tx.outputs.size == 2)
        "Business Network bootstrap transaction should have one output Membership state" using (tx.outputStates.any { it is MembershipState })
        "Business Network bootstrap transaction should have one output Group state" using (tx.outputStates.any { it is GroupState })

        val membership = tx.outputStates.single { it is MembershipState } as MembershipState
        val group = tx.outputStates.single { it is GroupState } as GroupState

        "Business Network bootstrap transaction should issue membership in ACTIVE status" using (membership.isActive())
        "Business Network bootstrap transaction should issue membership with single role" using (membership.roles.size == 1)
        "Business Network bootstrap transaction should issue membership with BNO role" using (membership.roles.single() is BNORole)
        "Business Network bootstrap transaction should issue membership with issuer field as identity" using (membership.issuer == membership.identity.cordaIdentity)
        "Business Network bootstrap transaction should issue membership with no other participants" using (membership.participants == listOf(
            membership.identity.cordaIdentity
        ))
        "Business Network bootstrap transaction should be signed only by issued member" using (listOf(membership.identity.cordaIdentity.owningKey) == command.value.requiredSigners)

        "Business Network bootstrap transaction should issue group with same network ID as member" using (group.networkId == membership.networkId)
        "Business Network bootstrap transaction should issue group with same issuer as member" using (group.issuer == membership.issuer)
        "Business Network bootstrap transaction should issue group with issued member as single participant" using (group.participants == listOf(
            membership.identity.cordaIdentity
        ))
    }

    /**
     * Contract verification check specific to [Commands.Request] command. Each contract extending [MembershipContract] can override this
     * method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about membership creation command.
     * @param outputMembership Output [MembershipState] of the transaction.
     */
    open fun verifyRequest(
        tx: LedgerTransaction,
        command: CommandWithParties<Commands>,
        outputMembership: MembershipState
    ) {
        requireThat {
            "Membership request transaction shouldn't contain any inputs" using (tx.inputs.isEmpty())
            "Membership request transaction should contain output state in PENDING status" using (outputMembership.isPending())
            "Membership request transaction should issue membership with empty roles set" using (outputMembership.roles.isEmpty())
            "Pending membership owner should be required signer of membership request transaction" using (outputMembership.identity.cordaIdentity.owningKey in command.value.requiredSigners)
        }
        verifyInitiator(tx, outputMembership.networkId, outputMembership, command.value.requiredSigners) {
            it.canActivateMembership()
        }
    }

    /**
     * Contract verification check specific to [Commands.Activate] command. Each contract extending [MembershipContract] can override this
     * method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about membership activation command.
     * @param inputMembership Input [MembershipState] of the transaction.
     * @param outputMembership Output [MembershipState] of the transaction.
     */
    open fun verifyActivate(
        tx: LedgerTransaction,
        command: CommandWithParties<Commands>,
        inputMembership: MembershipState,
        outputMembership: MembershipState
    ) {
        requireThat {
            "Input state of membership activation transaction shouldn't be already active" using (!inputMembership.isActive())
            "Output state of membership activation transaction should be active" using (outputMembership.isActive())
            "Input and output state of membership activation transaction should have same roles set" using (inputMembership.roles == outputMembership.roles)
            "Input and output state of membership activation transaction should have same Corda identity" using (inputMembership.identity.cordaIdentity == outputMembership.identity.cordaIdentity)
            "Input and output state of membership activation transaction should have same business identity" using (inputMembership.identity.businessIdentity == outputMembership.identity.businessIdentity)
            "Input and output state of membership activation transaction should have same participants" using (inputMembership.participants.toSet() == outputMembership.participants.toSet())
            "Input membership owner shouldn't be required signer of membership activation transaction" using (inputMembership.identity.cordaIdentity.owningKey !in command.value.requiredSigners)
        }
        verifyInitiator(tx, outputMembership.networkId, outputMembership, command.value.requiredSigners) {
            it.canActivateMembership()
        }
    }

    /**
     * Contract verification check specific to [Commands.Onboard] command. Each contract extending [MembershipContract] can override this
     * method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about membership creation command.
     * @param outputMembership Output [MembershipState] of the transaction.
     */
    open fun verifyOnboard(
        tx: LedgerTransaction,
        command: CommandWithParties<Commands>,
        outputMembership: MembershipState
    ) {
        requireThat {
            "Membership onboarding transaction shouldn't contain any inputs" using (tx.inputs.isEmpty())
            "Membership onboarding transaction should contain output state in ACTIVE status" using (outputMembership.isActive())
            "Membership onboarding transaction should issue membership with empty roles set" using (outputMembership.roles.isEmpty())
            "Onboarded membership owner should be required signer of membership onboarding transaction" using (outputMembership.identity.cordaIdentity.owningKey in command.value.requiredSigners)
        }
        verifyInitiator(tx, outputMembership.networkId, outputMembership, command.value.requiredSigners) {
            it.canActivateMembership()
        }
    }

    /**
     * Contract verification check specific to [Commands.Suspend] command. Each contract extending [MembershipContract] can override this
     * method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about membership suspension command.
     * @param inputMembership Input [MembershipState] of the transaction.
     * @param outputMembership Output [MembershipState] of the transaction.
     */
    open fun verifySuspend(
        tx: LedgerTransaction,
        command: CommandWithParties<Commands>,
        inputMembership: MembershipState,
        outputMembership: MembershipState
    ) {
        requireThat {
            "Input state of membership suspension transaction shouldn't be already suspended" using (!inputMembership.isSuspended())
            "Output state of membership suspension transaction should be suspended" using (outputMembership.isSuspended())
            "Input and output state of membership suspension transaction should have same roles set" using (inputMembership.roles == outputMembership.roles)
            "Input and output state of membership suspension transaction should have same Corda identity" using (inputMembership.identity.cordaIdentity == outputMembership.identity.cordaIdentity)
            "Input and output state of membership suspension transaction should have same business identity" using (inputMembership.identity.businessIdentity == outputMembership.identity.businessIdentity)
            "Input and output state of membership suspension transaction should have same participants" using (inputMembership.participants.toSet() == outputMembership.participants.toSet())
            "Input membership owner shouldn't be required signer of membership suspension transaction" using (inputMembership.identity.cordaIdentity.owningKey !in command.value.requiredSigners)
        }
        verifyInitiator(tx, outputMembership.networkId, outputMembership, command.value.requiredSigners) {
            it.canSuspendMembership()
        }
    }

    /**
     * Contract verification check specific to [Commands.Revoke] command. Each contract extending [MembershipContract] can override this
     * method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about membership revocation command.
     * @param inputMembership Input [MembershipState] of the transaction.
     */
    open fun verifyRevoke(
        tx: LedgerTransaction,
        command: CommandWithParties<Commands>,
        inputMembership: MembershipState
    ) {
        requireThat {
            "Membership revocation transaction shouldn't contain any outputs" using (tx.outputs.isEmpty())
            "Input membership owner shouldn't be required signer of membership revocation transaction" using (inputMembership.identity.cordaIdentity.owningKey !in command.value.requiredSigners)
        }
        verifyInitiator(tx, inputMembership.networkId, inputMembership, command.value.requiredSigners) {
            it.canRevokeMembership()
        }
    }

    /**
     * Contract verification check specific to [Commands.ModifyRoles] command. Each contract extending [MembershipContract] can override
     * this method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about membership role modification command.
     * @param inputMembership Input [MembershipState] of the transaction.
     * @param outputMembership Output [MembershipState] of the transaction.
     */
    open fun verifyModifyRoles(
        tx: LedgerTransaction,
        command: CommandWithParties<Commands>,
        inputMembership: MembershipState,
        outputMembership: MembershipState
    ) {
        val initiator = verifyInitiator(tx, outputMembership.networkId, outputMembership, command.value.requiredSigners) {
            it.canModifyRoles()
        }
        requireThat {
            "Input and output state of membership roles modification transaction should have same status" using (inputMembership.status == outputMembership.status)
            "Membership roles modification transaction can only be performed on active or suspended state" using (inputMembership.isActive() || inputMembership.isSuspended())
            "Input and output state of membership roles modification transaction should have different set of roles" using (inputMembership.roles != outputMembership.roles)
            "Input and output state of membership roles modification transaction should have same Corda identity" using (inputMembership.identity.cordaIdentity == outputMembership.identity.cordaIdentity)
            "Input and output state of membership roles modification transaction should have same business identity" using (inputMembership.identity.businessIdentity == outputMembership.identity.businessIdentity)
            "Input and output state of membership roles modification transaction should have same participants" using (inputMembership.participants.toSet() == outputMembership.participants.toSet())
            (command.value as Commands.ModifyRoles).apply {
                val selfModification = initiator.identity.cordaIdentity == inputMembership.identity.cordaIdentity
                val memberIsSigner = inputMembership.identity.cordaIdentity.owningKey in requiredSigners
                "Input membership owner should be required signer of membership roles modification transaction if it initiated it" using (!selfModification || memberIsSigner)
                "Input membership owner shouldn't be required signer of membership roles modification transaction if it didn't initiate it" using (selfModification || !memberIsSigner)
            }
        }
    }

    /**
     * Contract verification check specific to [Commands.ModifyCordaIdentity] command. Each contract extending [MembershipContract] can override
     * this method to implement their own custo created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about membership role modification command.
     * @param inputMembership Input [MembershipState] of the transaction.
     * @param outputMembership Output [MembershipState] of the transaction.
     */
    open fun verifyModifyCordaIdentity(
        tx: LedgerTransaction,
        command: CommandWithParties<Commands>,
        inputMembership: MembershipState,
        outputMembership: MembershipState
    ) = requireThat {
        "Input and output state of membership corda identity modification transaction should have same status" using (inputMembership.status == outputMembership.status)
        "Input and output state of membership corda identity modification transaction should have same roles" using (inputMembership.roles == outputMembership.roles)
        "Input and output state of membership corda identity modification transaction should have different Corda identity owning key or different owning key for one of the participants" using (inputMembership.identity.cordaIdentity.owningKey != outputMembership.identity.cordaIdentity.owningKey
                || inputMembership.participants.toSet() != outputMembership.participants.toSet())
        "Input and output state of membership corda identity modification transaction should have same Corda identity X500 name" using (inputMembership.identity.cordaIdentity.name == outputMembership.identity.cordaIdentity.name)
        "Input and output state of membership corda identity modification transaction should have same business identity" using (inputMembership.identity.businessIdentity == outputMembership.identity.businessIdentity)
    }

    /**
     * Contract verification check specific to [Commands.ModifyBusinessIdentity] command. Each contract extending [MembershipContract] can
     * override this method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about membership business identity modification command.
     * @param inputMembership Input [MembershipState] of the transaction.
     * @param outputMembership Output [MembershipState] of the transaction.
     */
    open fun verifyModifyBusinessIdentity(
        tx: LedgerTransaction,
        command: CommandWithParties<Commands>,
        inputMembership: MembershipState,
        outputMembership: MembershipState
    ) {
        val initiator = verifyInitiator(tx, outputMembership.networkId, outputMembership, command.value.requiredSigners) {
            it.canModifyBusinessIdentity()
        }
        requireThat {
            "Input and output state of membership business identity modification transaction should have same status" using (inputMembership.status == outputMembership.status)
            "Membership business identity modification transaction can only be performed on active or suspended state" using (inputMembership.isActive() || inputMembership.isSuspended())
            "Input and output state of membership business identity modification transaction should have same roles" using (inputMembership.roles == outputMembership.roles)
            "Input and output state of membership business identity modification transaction should have same Corda identity" using (inputMembership.identity.cordaIdentity == outputMembership.identity.cordaIdentity)
            "Input and output state of membership business identity modification transaction should have different business identity" using (inputMembership.identity.businessIdentity != outputMembership.identity.businessIdentity)
            "Input and output state of membership business identity modification transaction should have same participants" using (inputMembership.participants.toSet() == outputMembership.participants.toSet())
            (command.value as Commands.ModifyBusinessIdentity).apply {
                val selfModification = initiator.identity.cordaIdentity == inputMembership.identity.cordaIdentity
                val memberIsSigner = inputMembership.identity.cordaIdentity.owningKey in requiredSigners
                "Input membership owner should be required signer of membership business identity modification transaction if it initiated it" using (!selfModification || memberIsSigner)
                "Input membership owner shouldn't be required signer of membership business identity modification transaction if it didn't initiate it" using (selfModification || !memberIsSigner)
            }
        }
    }

    /**
     * Contract verification check specific to [Commands.ModifyParticipants] command. Each contract extending [MembershipContract] can
     * override this method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about membership participants modification command.
     * @param inputMembership Input [MembershipState] of the transaction.
     * @param outputMembership Output [MembershipState] of the transaction.
     */
    open fun verifyModifyParticipants(
        tx: LedgerTransaction,
        command: CommandWithParties<Commands>,
        inputMembership: MembershipState,
        outputMembership: MembershipState
    ) = requireThat {
        "Input and output state of membership participants modification transaction should have same status" using (inputMembership.status == outputMembership.status)
        "Membership participants modification transaction can only be performed on active or suspended state" using (inputMembership.isActive() || inputMembership.isSuspended())
        "Input and output state of membership participants modification transaction should have same roles" using (inputMembership.roles == outputMembership.roles)
        "Input and output state of membership participants modification transaction should have same Corda identity" using (inputMembership.identity.cordaIdentity == outputMembership.identity.cordaIdentity)
        "Input and output state of membership participants modification transaction should have same business identity" using (inputMembership.identity.businessIdentity == outputMembership.identity.businessIdentity)
    }
}
