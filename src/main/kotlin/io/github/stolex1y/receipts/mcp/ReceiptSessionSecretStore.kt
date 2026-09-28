package io.github.stolex1y.receipts.mcp

import com.github.javakeyring.Keyring
import com.github.javakeyring.PasswordAccessException
import org.freedesktop.dbus.ObjectPath
import org.freedesktop.secret.Collection as SecretCollection
import org.freedesktop.secret.Item as SecretItem
import org.freedesktop.secret.Prompt
import org.freedesktop.secret.Service
import org.freedesktop.secret.Static
import org.freedesktop.secret.TransportEncryption

private const val ROOT_OBJECT_PATH = "/"
private const val LOGIN_COLLECTION_SUFFIX = "/collection/login"
private const val SESSION_COLLECTION_SUFFIX = "/collection/session"
private const val DEFAULT_ALIAS = "default"

internal interface ReceiptSessionSecretStore {
    fun read(): String?
    fun write(value: String)
    fun delete()
}

internal class ReceiptSessionSecretStoreUnavailableException : IllegalStateException(
    "OS credential store is unavailable.",
)

internal data class ReceiptSessionSecretUnlockResult(
    val unlockedObjectPaths: List<String>,
    val promptPath: String,
)

internal interface ReceiptSessionSecretService : AutoCloseable {
    val defaultCollectionPath: String
    val collectionPaths: List<String>

    fun openCollection(path: String): ReceiptSessionSecretCollection
}

internal interface ReceiptSessionSecretCollection {
    val collectionPath: String

    fun isLocked(objectPath: String): Boolean

    fun unlock(objectPath: String): ReceiptSessionSecretUnlockResult?

    fun awaitPrompt(promptPath: String): Boolean

    fun findItems(attributes: Map<String, String>): List<String>

    fun read(itemPath: String): String

    fun write(
        itemPath: String?,
        label: String,
        attributes: Map<String, String>,
        replaceExisting: Boolean,
        value: String,
    )

    fun delete(itemPath: String, attributes: Map<String, String>)
}

internal class KeyringReceiptSessionSecretStore(
    private val linuxSecretServiceFactory: () -> ReceiptSessionSecretService = {
        DirectReceiptSessionSecretService()
    },
    private val isLinuxPlatform: () -> Boolean = {
        System.getProperty("os.name").equals("Linux", ignoreCase = true)
    },
) : ReceiptSessionSecretStore {
    override fun read(): String? {
        if (isLinuxPlatform()) {
            return withLinuxSecretService { service ->
                val items = findPersistentItems(service)
                if (items.size > 1) throw unavailable()
                items.singleOrNull()?.let { item ->
                    unlockIfNeeded(item.collection, item.itemPath)
                    item.collection.read(item.itemPath)
                }
            }
        }

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
        if (isLinuxPlatform()) {
            withLinuxSecretService { service ->
                val items = findPersistentItems(service)
                if (items.size > 1) throw unavailable()

                val item = items.singleOrNull()
                val collection = item?.collection ?: service.openCollection(
                    chooseReceiptPersistentCollection(
                        defaultPath = service.defaultCollectionPath,
                        collectionPaths = service.collectionPaths,
                    ),
                )
                unlockIfNeeded(collection)

                val matchingItems = collection.findItems(ITEM_ATTRIBUTES)
                if (item != null && matchingItems != listOf(item.itemPath)) {
                    throw unavailable()
                }
                if (item == null && matchingItems.size > 1) throw unavailable()
                val targetItemPath = item?.itemPath ?: matchingItems.singleOrNull()
                targetItemPath?.let { unlockIfNeeded(collection, it) }

                collection.write(
                    itemPath = targetItemPath,
                    label = ITEM_LABEL,
                    attributes = ITEM_ATTRIBUTES,
                    replaceExisting = targetItemPath == null,
                    value = value,
                )
                if (targetItemPath == null) {
                    // A concurrent first writer may atomically replace this item's path.
                    if (collection.findItems(ITEM_ATTRIBUTES).size != 1) throw unavailable()
                }
            }
            return
        }

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
        if (isLinuxPlatform()) {
            withLinuxSecretService { service ->
                findPersistentItems(service).forEach { item ->
                    unlockIfNeeded(item.collection, item.itemPath)
                    item.collection.delete(item.itemPath, ITEM_ATTRIBUTES)
                }
                if (findPersistentItems(service).isNotEmpty()) throw unavailable()
            }
            return
        }

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

    private fun unlockIfNeeded(
        collection: ReceiptSessionSecretCollection,
        itemPath: String? = null,
    ) {
        unlockObjectIfNeeded(collection, collection.collectionPath)
        if (itemPath != null) unlockObjectIfNeeded(collection, itemPath)
    }

    private fun unlockObjectIfNeeded(
        collection: ReceiptSessionSecretCollection,
        objectPath: String,
    ) {
        if (!collection.isLocked(objectPath)) return

        val result = collection.unlock(objectPath) ?: throw unavailable()
        if (result.promptPath == ROOT_OBJECT_PATH) {
            if (objectPath !in result.unlockedObjectPaths) throw unavailable()
        } else if (!collection.awaitPrompt(result.promptPath)) {
            throw unavailable()
        }
        if (collection.isLocked(objectPath)) throw unavailable()
    }

    private fun openKeyring(): Keyring =
        try {
            Keyring.create()
        } catch (_: Throwable) {
            throw unavailable()
        }

    private fun <T> withLinuxSecretService(
        operation: (ReceiptSessionSecretService) -> T,
    ): T {
        val service = try {
            linuxSecretServiceFactory()
        } catch (_: Throwable) {
            throw unavailable()
        }

        return try {
            operation(service)
        } catch (_: Throwable) {
            throw unavailable()
        } finally {
            try {
                service.close()
            } catch (_: Throwable) {
                // Do not expose backend details after a completed secret operation.
            }
        }
    }

    private fun findPersistentItems(
        service: ReceiptSessionSecretService,
    ): List<PersistentReceiptSecret> {
        val persistentCollectionPaths = service.collectionPaths.filter(::isPersistentCollection)
        if (persistentCollectionPaths.isEmpty()) throw unavailable()

        return buildList {
            for (collectionPath in persistentCollectionPaths) {
                val collection = service.openCollection(collectionPath)
                for (itemPath in collection.findItems(ITEM_ATTRIBUTES)) {
                    add(PersistentReceiptSecret(collection, itemPath))
                }
            }
        }
    }

    private fun isMissingCredential(error: PasswordAccessException): Boolean =
        error.message?.startsWith("No stored credentials match") == true

    private fun closeKeyring(keyring: Keyring) {
        try {
            keyring.close()
        } catch (_: Throwable) {
            // Secret operation already completed; never expose backend details.
        }
    }

    private fun unavailable(): ReceiptSessionSecretStoreUnavailableException =
        ReceiptSessionSecretStoreUnavailableException()

    private data class PersistentReceiptSecret(
        val collection: ReceiptSessionSecretCollection,
        val itemPath: String,
    )

    private companion object {
        const val SERVICE = "smart-expense-agent.receipts"
        const val ACCOUNT = "real-session"
        const val ITEM_LABEL = "$SERVICE $ACCOUNT"
        val ITEM_ATTRIBUTES = mapOf(
            "service" to SERVICE,
            "account" to ACCOUNT,
        )
    }
}

private class DirectReceiptSessionSecretService : ReceiptSessionSecretService {
    private val transport = TransportEncryption()
    private val service = transport.service

    init {
        try {
            transport.initialize()
            if (!transport.openSession()) throw ReceiptSessionSecretStoreUnavailableException()
            transport.generateSessionKey()
        } catch (_: Throwable) {
            close()
            throw ReceiptSessionSecretStoreUnavailableException()
        }
    }

    override val defaultCollectionPath: String
        get() = service.readAlias(DEFAULT_ALIAS)?.path
            ?: throw ReceiptSessionSecretStoreUnavailableException()

    override val collectionPaths: List<String>
        get() = service.collections?.map { it.path }
            ?: throw ReceiptSessionSecretStoreUnavailableException()

    override fun openCollection(path: String): ReceiptSessionSecretCollection =
        DirectReceiptSessionSecretCollection(path, service, transport)

    override fun close() {
        try {
            service.session?.close()
        } catch (_: Throwable) {
            // Cleanup must not hide the result of the secret operation.
        }
        try {
            transport.close()
        } catch (_: Throwable) {
            // Cleanup must not hide the result of the secret operation.
        }
        try {
            service.connection?.close()
        } catch (_: Throwable) {
            // Cleanup must not hide the result of the secret operation.
        }
    }
}

private class DirectReceiptSessionSecretCollection(
    collectionPath: String,
    private val service: Service,
    private val transport: TransportEncryption,
) : ReceiptSessionSecretCollection {
    override val collectionPath: String = collectionPath
    private val path: ObjectPath = Static.Convert.toObjectPath(collectionPath)
    private val collection = SecretCollection(path, service)

    override fun isLocked(objectPath: String): Boolean =
        if (objectPath == collectionPath) {
            collection.isLocked
        } else {
            SecretItem(Static.Convert.toObjectPath(objectPath), service).isLocked
        }

    override fun unlock(objectPath: String): ReceiptSessionSecretUnlockResult? {
        val result = service.unlock(listOf(Static.Convert.toObjectPath(objectPath))) ?: return null
        val unlockedObjectPaths = result.a?.map { it.path } ?: return null
        val promptPath = result.b?.path ?: return null
        return ReceiptSessionSecretUnlockResult(unlockedObjectPaths, promptPath)
    }

    override fun awaitPrompt(promptPath: String): Boolean {
        if (promptPath == ROOT_OBJECT_PATH) return true
        val result = Prompt(service).await(Static.Convert.toObjectPath(promptPath)) ?: return false
        return !result.dismissed
    }

    override fun findItems(attributes: Map<String, String>): List<String> =
        collection.searchItems(attributes)?.map { it.path }
            ?: throw ReceiptSessionSecretStoreUnavailableException()

    override fun read(itemPath: String): String {
        val secret = SecretItem(
            Static.Convert.toObjectPath(itemPath),
            service,
        ).getSecret(service.session?.path ?: throw ReceiptSessionSecretStoreUnavailableException())
            ?: throw ReceiptSessionSecretStoreUnavailableException()
        return try {
            val characters = transport.decrypt(secret)
                ?: throw ReceiptSessionSecretStoreUnavailableException()
            try {
                String(characters)
            } finally {
                characters.fill('\u0000')
            }
        } finally {
            secret.close()
        }
    }

    override fun write(
        itemPath: String?,
        label: String,
        attributes: Map<String, String>,
        replaceExisting: Boolean,
        value: String,
    ) {
        val secret = transport.encrypt(value)
            ?: throw ReceiptSessionSecretStoreUnavailableException()
        try {
            if (itemPath != null) {
                SecretItem(Static.Convert.toObjectPath(itemPath), service)
                    .setSecret(secret)
            } else {
                val result = collection.createItem(
                    SecretItem.createProperties(label, attributes),
                    secret,
                    replaceExisting,
                ) ?: throw ReceiptSessionSecretStoreUnavailableException()
                if (result.a == null) throw ReceiptSessionSecretStoreUnavailableException()
                val promptPath = result.b
                    ?: throw ReceiptSessionSecretStoreUnavailableException()
                if (!awaitPrompt(promptPath.path)) {
                    throw ReceiptSessionSecretStoreUnavailableException()
                }
            }
        } finally {
            secret.close()
        }
    }

    override fun delete(itemPath: String, attributes: Map<String, String>) {
        if (!findItems(attributes).contains(itemPath)) {
            throw ReceiptSessionSecretStoreUnavailableException()
        }
        val promptPath = SecretItem(
            Static.Convert.toObjectPath(itemPath),
            service,
        ).delete() ?: throw ReceiptSessionSecretStoreUnavailableException()
        if (!awaitPrompt(promptPath.path)) {
            throw ReceiptSessionSecretStoreUnavailableException()
        }
        if (findItems(attributes).contains(itemPath)) {
            throw ReceiptSessionSecretStoreUnavailableException()
        }
    }
}

internal fun chooseReceiptPersistentCollection(
    defaultPath: String,
    collectionPaths: List<String>,
): String {
    val persistentPaths = collectionPaths.filter(::isPersistentCollection)
    return persistentPaths.firstOrNull { it == defaultPath }
        ?: persistentPaths.firstOrNull { it.endsWith(LOGIN_COLLECTION_SUFFIX) }
        ?: persistentPaths.firstOrNull()
        ?: throw ReceiptSessionSecretStoreUnavailableException()
}

private fun isPersistentCollection(path: String): Boolean =
    path != ROOT_OBJECT_PATH && !path.endsWith(SESSION_COLLECTION_SUFFIX)
