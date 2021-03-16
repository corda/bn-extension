package net.corda.bn.contracts

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
 * Contract that verifies an evolution of [GroupState].
 */
open class GroupContract : BNContract {

    companion object {
        const val CONTRACT_NAME = "net.corda.bn.contracts.GroupContract"
    }

    /**
     * Each new [GroupContract] command must be wrapped and extend this class.
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
         * Command responsible for [GroupState] issuance.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Create(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for [GroupState] modification.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Modify(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for [GroupState] exit.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Exit(requiredSigners: List<PublicKey>) : Commands(requiredSigners)
    }

    /**
     * Ensures [GroupState] transition makes sense. Throws exception if there is a problem that should prevent the transition.
     *
     * @param tx Ledger transaction over which contract performs verification.
     */
    @Suppress("ComplexMethod")
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val input = if (tx.inputStates.isNotEmpty()) tx.inputs.singleOrNull { it.state.data is GroupState } else null
        val inputState = input?.state?.data as? GroupState
        val output = if (tx.outputStates.isNotEmpty()) tx.outputs.singleOrNull { it.data is GroupState } else null
        val outputState = output?.data as? GroupState

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
                "Group state issuer cannot be changed" using (inputState.issuer == outputState.issuer)
                "Output state's modified timestamp should be greater or equal than input's" using (inputState.modified <= outputState.modified)
                "Input and output state should have same linear IDs" using (inputState.linearId == outputState.linearId)
                "Transaction must be signed by all signers specified inside command" using (command.signers.toSet() == command.value.requiredSigners.toSet())
            }
        }

        when (command.value) {
            is Commands.Bootstrap -> verifyBootstrap(tx, command)
            is Commands.Create -> verifyCreate(tx, command, outputState!!)
            is Commands.Modify -> verifyModify(tx, command, inputState!!, outputState!!)
            is Commands.Exit -> verifyExit(tx, command, inputState!!)
            else -> throw IllegalArgumentException("Unsupported command ${command.value}")
        }
    }

    /**
     * Each contract extending [GroupContract] must override this method providing associated contract name.
     */
    open fun contractName() = CONTRACT_NAME

    /**
     * Contract verification check specific to [Commands.Bootstrap] command. Each contract extending [GroupContract] can override this
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
    }

    /**
     * Contract verification check specific to [Commands.Create] command. Each contract extending [GroupContract] can override this method
     * to implement their own custom create command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about group creation command.
     * @param outputGroup Output [GroupState] of the transaction.
     */
    open fun verifyCreate(tx: LedgerTransaction, command: CommandWithParties<Commands>, outputGroup: GroupState) {
        requireThat {
            "Group issuance transaction shouldn't contain any inputs" using (tx.inputs.isEmpty())
        }
        verifyInitiator(tx, outputGroup.networkId, outputGroup, command.value.requiredSigners) {
            it.canModifyGroups()
        }
    }

    /**
     * Contract verification check specific to [Commands.Modify] command. Each contract extending [GroupContract] can override this method
     * to implement their own custom modify command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about group modification command.
     * @param inputGroup Input [GroupState] of the transaction.
     * @param outputGroup Output [GroupState] of the transaction.
     */
    open fun verifyModify(
        tx: LedgerTransaction,
        command: CommandWithParties<Commands>,
        inputGroup: GroupState,
        outputGroup: GroupState
    ) {
        requireThat {
            "Input and output states of group modification transaction should have different name or participants field" using (inputGroup.name != outputGroup.name || inputGroup.participants.toSet() != outputGroup.participants.toSet())
        }
        verifyInitiator(tx, outputGroup.networkId, outputGroup, command.value.requiredSigners) {
            it.canModifyGroups()
        }
    }

    /**
     * Contract verification check specific to [Commands.Exit] command. Each contract extending [GroupContract] can override this method
     * to implement their own custom exit command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about group exit command.
     * @param inputGroup Input [GroupState] of the transaction.
     */
    open fun verifyExit(tx: LedgerTransaction, command: CommandWithParties<Commands>, inputGroup: GroupState) {
        requireThat {
            "Group exit transaction shouldn't contain any outputs" using (tx.outputs.isEmpty())
        }
        verifyInitiator(tx, inputGroup.networkId, inputGroup, command.value.requiredSigners) {
            it.canModifyGroups()
        }
    }
}
