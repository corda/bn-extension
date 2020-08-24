package net.corda.bn.schemas

import net.corda.core.schemas.MappedSchema
import java.io.Serializable
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table
import javax.persistence.UniqueConstraint

/**
 * A database schema configured to represent Business Network request.
 */
class BNRequestSchema

/**
 * Version 1 of [BNRequestSchema].
 */
object BNRequestSchemaV1 : MappedSchema(schemaFamily = BNRequestSchema::class.java, version = 1, mappedTypes = listOf(PersistentBNRequest::class.java)) {

    override val migrationResource = "bn-request-schema-v1.changelog-master"

    /**
     * Represents Business Network request used to prevent having multiple identical requests of same type in-flight.
     *
     * @property id ID of the Business Network request.
     * @property type Type of the Business Network request.
     * @property data Data of the Business Network request.
     */
    @Entity(name = "PersistentBNRequest")
    @Table(name = "persistent_bn_request", uniqueConstraints = [UniqueConstraint(columnNames = ["type", "data"])])
    class PersistentBNRequest(
            @Id
            @GeneratedValue(strategy = GenerationType.AUTO)
            @Column(name = "id", nullable = false)
            val id: Long = 0,

            @Column(name = "type", nullable = false)
            val type: BNRequestType,

            @Column(name = "data", nullable = false)
            val data: String
    ) : Serializable
}

/**
 * Represents all possible types of Business Network requests which could cause problems when multiple called in-flight.
 */
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