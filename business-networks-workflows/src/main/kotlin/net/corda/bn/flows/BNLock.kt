package net.corda.bn.flows

import net.corda.core.serialization.CordaSerializable

/**
 * Represents Business Network request used to prevent having multiple identical requests of same type in-flight.
 *
 * @property type Type of the Business Network request.
 * @property data Data of the Business Network request.
 */
@CordaSerializable
data class BNLock(val type: BNRequestType, val data: String)

/**
 * Represents all possible types of Business Network requests which could cause problems when multiple called in-flight.
 */
@CordaSerializable
enum class BNRequestType {

    /**
     * Request for issuance of the Business Network with custom network ID.
     */
    BUSINESS_NETWORK_ID,

    /**
     * Request for issuance of the Business Network Group with custom group ID.
     */
    BUSINESS_NETWORK_GROUP_ID,

    /**
     * Request for issuance of the Business Network Group with custom group name.
     */
    BUSINESS_NETWORK_GROUP_NAME,

    /**
     * Pending membership request.
     */
    PENDING_MEMBERSHIP
}
