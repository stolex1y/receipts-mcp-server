package io.github.stolex1y.receipts.mcp

import com.github.javakeyring.Keyring
import com.github.javakeyring.KeyringStorageType
import com.github.javakeyring.PasswordAccessException
import org.freedesktop.secret.TransportEncryption

private const val ROOT_OBJECT_PATH = "/"
private const val LOGIN_COLLECTION_SUFFIX = "/collection/login"
private const val SESSION_COLLECTION_SUFFIX = "/collection/session"

internal interface ReceiptSessionSecretStore {
    fun read(): String?
    fun write(value: String)
    fun delete()
}

internal class ReceiptSessionSecretStoreUnavailableException : IllegalStateException(
    "OS credential store is unavailable.",
)

internal class KeyringReceiptSessionSecretStore : ReceiptSessionSecretStore {
    override fun read(): String? {
        val keyring = openKeyring()
        return try {
            try {
                keyring.getPassword(SERVICE, ACCOUNT)
            } catch (error: PasswordAccessException) {
                if (isMissingCredential(error)) null else throw unavailable()
            } catch (_: Throwable) {
                throw unavailable()
            }
        } finally {
            closeKeyring(keyring)
        }
    }

    override fun write(value: String) {
        val keyring = openKeyring()
        try {
            try {
                keyring.setPassword(SERVICE, ACCOUNT, value)
            } catch (_: Throwable) {
                throw unavailable()
            }
        } finally {
            closeKeyring(keyring)
        }
    }

    override fun delete() {
        val keyring = openKeyring()
        try {
            try {
                keyring.deletePassword(SERVICE, ACCOUNT)
            } catch (error: PasswordAccessException) {
                if (!isMissingCredential(error)) throw unavailable()
            } catch (_: Throwable) {
                throw unavailable()
            }
        } finally {
            closeKeyring(keyring)
        }
    }

    private fun openKeyring(): Keyring {
        return try {
            val keyring = Keyring.create()
            if (keyring.getKeyringStorageType() != KeyringStorageType.GNOME_KEYRING) {
                keyring
            } else {
                closeKeyring(keyring)
                ensureDefaultCollection()
                Keyring.create()
            }
        } catch (_: Throwable) {
            throw unavailable()
        }
    }

    private fun ensureDefaultCollection() {
        if (!System.getProperty("os.name").equals("Linux", ignoreCase = true)) return

        val transport = TransportEncryption()
        try {
            val service = transport.service
            val defaultCollection = service.readAlias(DEFAULT_ALIAS)
            val collectionPath = chooseReceiptPersistentCollection(
                defaultPath = defaultCollection.path,
                collectionPaths = service.collections.map { it.path },
            ) ?: return
            service.setAlias(
                DEFAULT_ALIAS,
                org.freedesktop.secret.Static.Convert.toObjectPath(collectionPath),
            )
        } finally {
            transport.close()
        }
    }

    private fun closeKeyring(keyring: Keyring) {
        try {
            keyring.close()
        } catch (_: Throwable) {
            // Secret operation already completed; never expose backend details.
        }
    }

    private fun isMissingCredential(error: PasswordAccessException): Boolean =
        error.message?.startsWith("No stored credentials match") == true

    private fun unavailable(): ReceiptSessionSecretStoreUnavailableException =
        ReceiptSessionSecretStoreUnavailableException()

    private companion object {
        const val SERVICE = "smart-expense-agent.receipts"
        const val ACCOUNT = "real-session"
        const val DEFAULT_ALIAS = "default"
    }
}

internal fun chooseReceiptPersistentCollection(
    defaultPath: String,
    collectionPaths: List<String>,
): String? {
    if (defaultPath != ROOT_OBJECT_PATH && !defaultPath.endsWith(SESSION_COLLECTION_SUFFIX)) {
        return null
    }
    return collectionPaths.firstOrNull { it.endsWith(LOGIN_COLLECTION_SUFFIX) }
        ?: collectionPaths.firstOrNull { !it.endsWith(SESSION_COLLECTION_SUFFIX) }
}
