package net.corda.bn.schemas

import net.corda.bn.states.GroupState
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.TypeDef
import org.hibernate.type.StringType
import java.sql.Types
import javax.persistence.AttributeOverride
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table
import jakarta.persistence.Column as JakartaColumn
import jakarta.persistence.Entity as JakartaEntity
import jakarta.persistence.Table as JakartaTable
import org.hibernate.annotations.Target as HibernateTarget

/**
 * A database schema configured to represent [GroupState].
 */
object GroupStateSchemaV1 : MappedSchema(schemaFamily = GroupState::class.java, version = 1, mappedTypes = listOf(PersistentGroupState::class.java)) {

    /**
     * Mapped [GroupState] to be exported to a schema.
     *
     * The entity carries both the `javax.persistence` (Corda 4.14 and earlier, Hibernate 5) and the `jakarta.persistence` (Corda 4.15,
     * Hibernate 7) annotations, with identical attributes, so that one jar runs on both: Hibernate reads the set it understands and the
     * JVM drops the annotations whose classes are not on the node's classpath. Neither API jar is bundled with the CorDapp.
     *
     * The changelog creates the string columns as `VARCHAR`. Corda Enterprise maps string attributes as nationalised character data
     * (`NVARCHAR`) on Oracle and SQL Server: Hibernate 5 (Corda 4.12 to 4.14) then rejects the columns at the node's schema validation
     * (`wrong column type encountered in column [...] in table [group_state]`), and Hibernate 7 (Corda 4.15) binds `NVARCHAR` values
     * to them. So the columns carry a `VARCHAR` mapping for each Hibernate version, in annotations the other version does not have,
     * which the JVM drops:
     * - the attributes the CorDapp declares: Hibernate 5's `@Target(VarcharString)` binds them as a [VarcharString], which the class's
     *   `@TypeDef` maps to `string` (a `VARCHAR`); Hibernate 7's `@JdbcTypeCode(Types.VARCHAR)` does the same;
     * - `transaction_id` belongs to Corda's [net.corda.core.schemas.PersistentStateRef], whose mapping a CorDapp cannot change. Its
     *   `javax.persistence` column override declares the `VARCHAR2(64)` the changelog creates, which Hibernate 5's validation accepts
     *   (it also compares the declared definition with the type name the database reports: `VARCHAR2` on Oracle, `varchar` on SQL
     *   Server); the values are still bound as `NVARCHAR`. Hibernate 7's validation accepts `NVARCHAR` for a `VARCHAR` column as it is.
     *
     * @property networkId Mapped column for [GroupState.networkId].
     * @property name Mapped column for [GroupState.name].
     */
    @Entity
    @JakartaEntity
    @Table(name = "group_state")
    @JakartaTable(name = "group_state")
    @AttributeOverride(name = "stateRef.txId", column = Column(name = "transaction_id", nullable = false, columnDefinition = "VARCHAR2(64)"))
    @TypeDef(typeClass = StringType::class, defaultForType = PersistentGroupState.VarcharString::class)
    class PersistentGroupState(
            @Column(name = "network_id")
            @JakartaColumn(name = "network_id")
            @HibernateTarget(VarcharString::class)
            @JdbcTypeCode(Types.VARCHAR)
            var networkId: String? = null,
            @Column(name = "name")
            @JakartaColumn(name = "name")
            @HibernateTarget(VarcharString::class)
            @JdbcTypeCode(Types.VARCHAR)
            var name: String? = null
    ) : PersistentState() {
        /** Never instantiated: only the key of the Hibernate 5 type definition that maps a string attribute as `VARCHAR` (see [PersistentGroupState]). */
        class VarcharString private constructor()
    }
}
