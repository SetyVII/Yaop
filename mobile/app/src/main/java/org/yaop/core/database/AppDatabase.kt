package org.yaop.core.database

import android.content.Context
import androidx.core.content.edit
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.yaop.core.crypto.AndroidKeyStoreVault
import org.yaop.core.model.DefaultTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ObjectEntity::class,
        ObjectLinkEntity::class,
        ObjectTypeEntity::class,
        AssetEntity::class,
        ObjectFtsEntity::class,
        OutboxOpEntity::class,
        VaultMetaEntity::class,
        VaultDomainSeqEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun objectDao(): ObjectDao

    companion object {
        private const val DB_NAME = "yaop_encrypted.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(
            context: Context,
            vault: AndroidKeyStoreVault,
            json: Json,
        ): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, vault, json).also { INSTANCE = it }
            }
        }

        /** Schema migrations, one per version bump (1 → 2 → …). Empty: this is version 1. */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()

        private fun buildDatabase(
            context: Context,
            vault: AndroidKeyStoreVault,
            json: Json,
        ): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passBytes = getOrCreateDbPassphrase(context, vault)
            val openHelperFactory = SupportOpenHelperFactory(passBytes)

            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            )
                .openHelperFactory(openHelperFactory)
                .addCallback(
                    object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            syncDefaultTypes()
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            syncDefaultTypes()
                        }

                        private fun syncDefaultTypes() {
                            CoroutineScope(Dispatchers.IO).launch {
                                val dao = INSTANCE?.objectDao() ?: return@launch
                                val typeEntities = DefaultTypes.ALL.map { type ->
                                    ObjectTypeEntity(
                                        id = type.id,
                                        name = type.name,
                                        payloadJson = json.encodeToString(type),
                                    )
                                }
                                dao.upsertTypes(typeEntities)
                            }
                        }
                    },
                )
                // No destructive fallback: the outbox lives in this DB. A version bump
                // without its Migration must fail loudly, never wipe unsynced captures.
                .addMigrations(*MIGRATIONS)
                .build()
        }

        private fun getOrCreateDbPassphrase(
            context: Context,
            vault: AndroidKeyStoreVault,
        ): ByteArray {
            val prefs = context.getSharedPreferences("yaop_db_security", Context.MODE_PRIVATE)
            val stored = prefs.getString("encrypted_db_key", null)
            val aad = "yaop_db_seed".toByteArray(Charsets.UTF_8)

            if (stored != null) {
                val decoded = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
                return runCatching { vault.decrypt(decoded, aad) }.getOrElse {
                    createNewPassphrase(prefs, vault, aad)
                }
            }
            return createNewPassphrase(prefs, vault, aad)
        }

        private fun createNewPassphrase(
            prefs: android.content.SharedPreferences,
            vault: AndroidKeyStoreVault,
            aad: ByteArray,
        ): ByteArray {
            val newPass = AndroidKeyStoreVault.generateRandomBytes(32)
            val encrypted = vault.encrypt(newPass, aad)
            val encoded: String? = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
            prefs.edit { putString("encrypted_db_key", encoded) }
            return newPass
        }
    }
}
