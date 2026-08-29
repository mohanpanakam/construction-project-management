package com.panakam.construction.backend.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.panakam.construction.backend.db.Units
import com.panakam.construction.backend.db.Users
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseFactory {

    fun init() {
        val jdbcUrl  = System.getenv("DB_URL")      ?: "jdbc:postgresql://localhost:5432/construction"
        val user     = System.getenv("DB_USER")     ?: "postgres"
        val password = System.getenv("DB_PASSWORD") ?: "postgres"

        val config = HikariConfig().apply {
            this.jdbcUrl         = jdbcUrl
            this.username        = user
            this.password        = password
            driverClassName      = "org.postgresql.Driver"
            maximumPoolSize      = 10
            isAutoCommit         = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }

        Database.connect(HikariDataSource(config))

        // Auto-create tables if they don't exist
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users,
                Projects,
                Units,
                Inventory,
                Financials,
                ProjectFiles
            )
        }
    }

    /** Run a suspend database query on the IO dispatcher. */
    suspend fun <T> dbQuery(block: () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}

