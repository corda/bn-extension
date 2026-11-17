package net.corda.bn.schemas

import net.corda.node.services.persistence.AbstractPartyToX500NameAsStringConverter
import org.hibernate.boot.Metadata
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.model.naming.Identifier
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.hibernate.mapping.Column
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.JDBCType

/**
 * Corda Enterprise maps string-valued attributes as nationalised character data (`NVARCHAR`) on Oracle and SQL Server: its session
 * factories for those databases call `MetadataBuilder.enableGlobalNationalizedCharacterDataSupport(true)`. The changelogs create the
 * string columns of `group_state` and `membership_state` as `VARCHAR`, so the entities must map them in a way the node's schema
 * validation accepts against those columns, on the Hibernate of every Corda version the CorDapp runs on; otherwise a Corda Enterprise
 * 4.12 to 4.14 node (Hibernate 5) does not start on Oracle or SQL Server:
 * ```
 * Schema-validation: wrong column type encountered in column [transaction_id] in table [group_state]; found [varchar2 (Types#VARCHAR)],
 * but expecting [nvarchar2(144) (Types#NVARCHAR)]
 * ```
 * Hibernate 7 (Corda 4.15) accepts an `NVARCHAR` mapping of a `VARCHAR` column in its validation.
 *
 * The test builds the mapping as the Enterprise Oracle session factory does, without a database, and checks what the validation
 * compares with the column: the JDBC type, or for the columns whose type Corda itself decides (`transaction_id` of
 * [net.corda.core.schemas.PersistentStateRef], `corda_identity` through the node's `AbstractParty` converter) the declared column
 * definition, which Hibernate 5 accepts when it starts with the type name the database reports (`VARCHAR2` on Oracle, `varchar` on SQL
 * Server). It runs on the Hibernate of the Corda version the CorDapp is built against: Hibernate 5 by default, Hibernate 7 with
 * `-PcordaVersion=4.15...`.
 */
class SchemaMappingTest {
    private val hibernate7 = runCatching { Class.forName("org.hibernate.annotations.JdbcTypeCode") }.isSuccess

    @Test(timeout = 300_000)
    fun `string columns the CorDapp declares are mapped as VARCHAR when strings are nationalised`() {
        withNationalisedMetadata { metadata ->
            for ((table, column) in listOf("group_state" to "network_id", "group_state" to "name", "membership_state" to "network_id")) {
                assertEquals("JDBC type of $table.$column", JDBCType.VARCHAR, jdbcType(column(metadata, table, column), metadata))
            }
        }
    }

    @Test(timeout = 300_000)
    fun `columns Corda maps itself pass the schema validation against the VARCHAR columns the changelogs create`() {
        withNationalisedMetadata { metadata ->
            for ((table, name) in listOf("group_state" to "transaction_id", "membership_state" to "transaction_id", "membership_state" to "corda_identity")) {
                val column = column(metadata, table, name)
                if (hibernate7) {
                    // Hibernate 7 validates VARCHAR and NVARCHAR as equivalent types.
                    assertTrue("JDBC type of $table.$name", jdbcType(column, metadata) in setOf(JDBCType.VARCHAR, JDBCType.NVARCHAR))
                } else {
                    // Hibernate 5 compares the declared column definition with the database's type name when the JDBC types differ.
                    val declared = column.sqlType?.lowercase()
                    assertTrue("declared definition of $table.$name: $declared", declared != null && declared.startsWith("varchar2"))
                }
            }
        }
    }

    @Test(timeout = 300_000)
    fun `corda_identity is bound as VARCHAR on Hibernate 7`() {
        if (!hibernate7) return
        withNationalisedMetadata { metadata ->
            assertEquals(JDBCType.VARCHAR, jdbcType(column(metadata, "membership_state", "corda_identity"), metadata))
        }
    }

    private fun withNationalisedMetadata(check: (Metadata) -> Unit) {
        val registry = StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", oracleDialect())
                // Build the mapping without connecting to a database: the Hibernate 5 and the Hibernate 7 setting.
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .build()
        try {
            val metadata = MetadataSources(registry)
                    .addAnnotatedClass(GroupStateSchemaV1.PersistentGroupState::class.java)
                    .addAnnotatedClass(MembershipStateSchemaV1.PersistentMembershipState::class.java)
                    .metadataBuilder
                    // The node maps Party attributes with this converter.
                    .applyAttributeConverter(AbstractPartyToX500NameAsStringConverter({ null }, { null }))
                    // As Corda Enterprise's Oracle and SQL Server session factories do.
                    .enableGlobalNationalizedCharacterDataSupport(true)
                    .build()
            check(metadata)
        } finally {
            StandardServiceRegistryBuilder.destroy(registry)
        }
    }

    private fun column(metadata: Metadata, table: String, name: String): Column {
        val entity = metadata.entityBindings.single { it.table.name.equals(table, ignoreCase = true) }
        return entity.table.getColumn(Identifier.toIdentifier(name)) ?: throw AssertionError("$table has no column $name")
    }

    private fun jdbcType(column: Column, metadata: Metadata): JDBCType = JDBCType.valueOf(column.getSqlTypeCode(metadata))

    /** The dialect Corda Enterprise 4.12 to 4.14 resolves on Oracle 12c and later (Hibernate 5), or Hibernate 7's (Corda 4.15). */
    private fun oracleDialect(): String {
        return listOf("org.hibernate.dialect.Oracle12cDialect", "org.hibernate.dialect.OracleDialect").first { name ->
            runCatching { Class.forName(name) }.isSuccess
        }
    }
}
