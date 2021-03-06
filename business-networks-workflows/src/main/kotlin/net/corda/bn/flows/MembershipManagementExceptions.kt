package net.corda.bn.flows

import net.corda.bn.states.GroupState
import net.corda.bn.states.MembershipState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowException

/**
 * Exception thrown whenever Business Network with [MembershipState.networkId] already exists.
 *
 * @property networkId ID of the already existing Business Network.
 */
class DuplicateBusinessNetworkException(val networkId: UniqueIdentifier) : FlowException("Business Network with $networkId ID already exists")

/**
 * Exception thrown whenever Business Network Group with [GroupState.linearId] already exists.
 */
class DuplicateBusinessNetworkGroupException(message: String) : FlowException(message)

/**
 * Exception thrown whenever Business Network request with [type] and [data] already exists in lock storage.
 *
 * @property type Type of the Business Network request.
 * @property data Data of the Business Network request.
 */
class DuplicateBusinessNetworkRequestException(val type: BNRequestType, val data: String) : FlowException("Request of $type type with $data data already exists")

/**
 * Exception thrown by any [MembershipManagementFlow] whenever Business Network with provided [MembershipState.networkId] doesn't exist.
 */
class BusinessNetworkNotFoundException(message: String) : FlowException(message)

/**
 * Exception thrown by any [MembershipManagementFlow] whenever provided parties membership doesn't exist.
 */
class MembershipNotFoundException(message: String) : FlowException(message)

/**
 * Exception thrown by any [MembershipManagementFlow] whenever provided parties membership attribute change request doesn't exist.
 */
class MembershipChangeRequestNotFoundException(message: String) : FlowException(message)

/**
 * Exception thrown by any [MembershipManagementFlow] whenever provided member's state is not appropriate for the context.
 */
class IllegalMembershipStatusException(message: String) : FlowException(message)

/**
 * Exception thrown by any [MembershipManagementFlow] whenever membership fails role based authorisation.
 */
class MembershipAuthorisationException(message: String) : FlowException(message)

/**
 * Exception thrown by any [MembershipManagementFlow] whenever Business Network group with provided [GroupState.linearId] doesn't exist.
 */
class BusinessNetworkGroupNotFoundException(message: String) : FlowException(message)

/**
 * Exception thrown by any [MembershipManagementFlow] whenever member remains without participation in any Business Network Group.
 */
class MembershipMissingGroupParticipationException(message: String) : FlowException(message)

/**
 * [MembershipManagementFlow] version of [IllegalArgumentException]
 */
class IllegalFlowArgumentException(message: String) : FlowException(message)

/**
 * Exception thrown by any [MembershipManagementFlow] whenever group ends up in illegal state.
 */
class IllegalBusinessNetworkGroupStateException(message: String) : FlowException(message)

/**
 * Exception thrown by any [MembershipManagementFlow] whenever the network is left in an invalid state(i.e. no members with permission to revoke memberships)
 */
class InvalidBusinessNetworkStateException(message: String) : FlowException(message)
