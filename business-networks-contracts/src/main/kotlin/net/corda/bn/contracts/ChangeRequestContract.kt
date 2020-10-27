package net.corda.bn.contracts

import net.corda.core.transactions.LedgerTransaction
import net.corda.bn.states.ChangeRequestState
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.contracts.CommandWithParties
import java.lang.IllegalArgumentException
import java.security.PublicKey

/**
 * Contract that verifies an evolution of [ChangeRequestState].
 */
open class ChangeRequestContract : Contract {
    companion object {
        const val CONTRACT_NAME = "net.corda.bn.contracts.ChangeRequestContract"
    }

    /**
     * Each new [ChangeRequestContract] command must be wrapped and extend this class.
     *
     * @property requiredSigners List of all required public keys of command's signers.
     */
    open class Commands(val requiredSigners: List<PublicKey>) : CommandData {
        /**
         * Command responsible for pending [ChangeRequestState] issuance.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Request(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for [ChangeRequestState] approval.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Approve(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for [ChangeRequestState] rejection.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Decline(requiredSigners: List<PublicKey>) : Commands(requiredSigners)

        /**
         * Command responsible for [ChangeRequestState] removal.
         *
         * @param requiredSigners List of all required public keys of command's signers.
         */
        class Delete(requiredSigners: List<PublicKey>) : Commands(requiredSigners)
    }

    /**
     * Ensures [ChangeRequestState] transition makes sense. Throws exception if there is a problem that should prevent the transition.
     *
     * @param tx Ledger transaction over which contract performs verification.
     */
    @Suppress("ComplexMethod")
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val input = if (tx.inputStates.isNotEmpty()) tx.inputs.single() else null
        val inputState = input?.state?.data as? ChangeRequestState
        val output = if (tx.outputStates.isNotEmpty()) tx.outputs.single() else null
        val outputState = output?.data as? ChangeRequestState

        requireThat {
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
            }
            if (inputState != null && outputState != null) {
                "Input and output state should have same issued timestamps" using (inputState.issued == outputState.issued)
                "Output state's modified timestamp should be greater or equal than input's" using (inputState.modified <= outputState.modified)
                "Input and output state should have same linear IDs" using (inputState.linearId == outputState.linearId)
                "Transaction must be signed by all signers specified inside command" using (command.signers.toSet() == command.value.requiredSigners.toSet())
            }
        }

        when (command.value) {
            is Commands.Request -> verifyRequest(tx, command, outputState!!)
            is Commands.Approve -> verifyApproval(tx, command, inputState!!, outputState!!)
            is Commands.Decline -> verifyRejection(tx, command, inputState!!, outputState!!)
            is Commands.Delete -> verifyRemoval(tx, command, inputState!!)
            else -> throw IllegalArgumentException("Unsupported command ${command.value}")
        }
    }

    /**
     * Each contract extending [ChangeRequestContract] must override this method providing associated contract name.
     */
    open fun contractName() = CONTRACT_NAME

    /**
     * Contract verification check specific to [Commands.Request] command. Each contract extending [ChangeRequestContract] can override this
     * method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about change request creation command.
     * @param outputChangeRequest Output [ChangeRequestState] of the transaction.
     */
    open fun verifyRequest(tx: LedgerTransaction, command: CommandWithParties<Commands>, outputChangeRequest: ChangeRequestState) = requireThat {
        "Membership change request transaction shouldn't contain any inputs" using (tx.inputs.isEmpty())
        "Membership change request transaction should contain output state in PENDING status" using (outputChangeRequest.isPending())
        "Membership change request transaction should issue request with non-empty roles change set or non-empty business identity change" using (outputChangeRequest.proposedRoleChange != null || outputChangeRequest.proposedBusinessIdentityChange != null)
    }

    /**
     * Contract verification check specific to [Commands.Approve] command. Each contract extending [ChangeRequestContract] can override this
     * method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about change request approval command.
     * @param inputChangeRequest Input [ChangeRequestState] of the transaction.
     * @param outputChangeRequest Output [ChangeRequestState] of the transaction.
     */
    open fun verifyApproval(
            tx: LedgerTransaction,
            command: CommandWithParties<Commands>,
            inputChangeRequest: ChangeRequestState,
            outputChangeRequest: ChangeRequestState
    ) = requireThat {
        "Input state of membership change request transaction shouldn't be already approved or declined" using (inputChangeRequest.isPending())
        "Output state of membership change request transaction should be approved" using (outputChangeRequest.isApproved())
        "Input and output state of membership change request transaction should have same roles set" using (inputChangeRequest.proposedRoleChange == outputChangeRequest.proposedRoleChange)
        "Input and output state of membership change request transaction should have same business identity" using (inputChangeRequest.proposedBusinessIdentityChange == outputChangeRequest.proposedBusinessIdentityChange)
        "Input and output state of membership change request transaction should have same participants" using (inputChangeRequest.participants == outputChangeRequest.participants)
    }

    /**
     * Contract verification check specific to [Commands.Decline] command. Each contract extending [ChangeRequestContract] can override this
     * method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about change request approval command.
     * @param inputChangeRequest Input [ChangeRequestState] of the transaction.
     * @param outputChangeRequest Output [ChangeRequestState] of the transaction.
     */
    open fun verifyRejection(
            tx: LedgerTransaction,
            command: CommandWithParties<Commands>,
            inputChangeRequest: ChangeRequestState,
            outputChangeRequest: ChangeRequestState
    ) = requireThat {
        "Input state of membership change request transaction shouldn't be already approved or declined" using (inputChangeRequest.isPending())
        "Output state of membership change request transaction should be declined" using (outputChangeRequest.isDeclined())
        "Input and output state of membership change request transaction should have same roles set" using (inputChangeRequest.proposedRoleChange == outputChangeRequest.proposedRoleChange)
        "Input and output state of membership change request transaction should have same business identity" using (inputChangeRequest.proposedBusinessIdentityChange == outputChangeRequest.proposedBusinessIdentityChange)
        "Input and output state of membership change request transaction should have same participants" using (inputChangeRequest.participants == outputChangeRequest.participants)
    }

    /**
     * Contract verification check specific to [Commands.Delete] command. Each contract extending [ChangeRequestContract] can override this
     * method to implement their own custom created command verification logic.
     *
     * @param tx Ledger transaction over which contract performs verification.
     * @param command Command with parties data about change request approval command.
     * @param inputChangeRequest Input [ChangeRequestState] of the transaction.
     */
    open fun verifyRemoval(tx: LedgerTransaction, command: CommandWithParties<Commands>, inputChangeRequest: ChangeRequestState) = requireThat {
        "Membership change request transaction shouldn't contain any outputs" using (tx.outputs.isEmpty())
    }
}