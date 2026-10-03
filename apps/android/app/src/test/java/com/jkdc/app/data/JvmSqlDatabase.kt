package com.jkdc.app.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * [SqlDatabase] over a JDBC connection (sqlite-jdbc), the JVM test twin of
 * [AndroidSqlDatabase]: the same interface and semantics so DAO tests run on
 * the desktop. Test source set only.
 */
class JvmSqlDatabase(val connection: Connection) : SqlDatabase {

    override fun exec(statement: String) {
        connection.createStatement().use { it.execute(statement) }
    }

    override fun insert(table: String, row: Map<String, Any?>, strategy: ConflictStrategy): Long {
        val columns = row.keys.toList()
        val sql = "INSERT OR $strategy INTO $table " +
            "(${columns.joinToString(", ")}) VALUES (${columns.joinToString(", ") { "?" }})"
        return connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { st ->
            bind(st, columns.map(row::getValue))
            if (st.executeUpdate() == 0) return -1L
            st.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else -1L }
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Row> =
        connection.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.row()) } }
        }

    private fun bind(st: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, value ->
            when (value) {
                null -> st.setNull(i + 1, Types.NULL)
                is Boolean -> st.setInt(i + 1, if (value) 1 else 0)
                is Int -> st.setInt(i + 1, value)
                is Long -> st.setLong(i + 1, value)
                is Double -> st.setDouble(i + 1, value)
                is String -> st.setString(i + 1, value)
                is ByteArray -> st.setBytes(i + 1, value)
                else -> error("Unsupported SQLite value: ${value::class.java.name}")
            }
        }
    }

    private fun ResultSet.row(): Row {
        val meta = metaData
        val names = (1..meta.columnCount).map(meta::getColumnLabel)
        val values = names.indices.map { i ->
            val index = i + 1
            if (getObject(index) == null) return@map null
            when (meta.getColumnType(index)) {
                Types.INTEGER -> getLong(index)
                Types.REAL, Types.DOUBLE, Types.FLOAT -> getDouble(index)
                Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> getBytes(index)
                else -> getString(index)
            }
        }
        return Row(names, values)
    }
}

/**
 * LearningDb.open for JVM tests: runs the canonical schema over an in-memory
 * (or file-backed) sqlite-jdbc connection with foreign keys enforced.
 */
fun LearningDb.openJvm(
    connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:"),
): SqlDatabase {
    val db = JvmSqlDatabase(connection)
    db.exec("PRAGMA foreign_keys = ON")
    for (statement in SchemaData.LEARNING_SCHEMA_STATEMENTS) db.exec(statement)
    return db
}
