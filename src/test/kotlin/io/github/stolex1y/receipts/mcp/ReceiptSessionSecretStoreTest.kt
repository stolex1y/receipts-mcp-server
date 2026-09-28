package io.github.stolex1y.receipts.mcp

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReceiptSessionSecretStoreTest {
    @Test
    fun rootDefaultUsesExplicitLoginItemWithoutChangingDefaultAlias() {
        val service = FakeReceiptSessionSecretService(
            defaultCollectionPath = ROOT_PATH,
            collectionPaths = listOf(SESSION_PATH, LOGIN_PATH),
        )
        val store = KeyringReceiptSessionSecretStore({ service }) { true }

        store.write("receipt-session-secret")

        assertEquals("receipt-session-secret", store.read())
        assertEquals(
            mapOf("service" to SERVICE, "account" to ACCOUNT),
            service.collections.getValue(LOGIN_PATH).writtenAttributes.single(),
        )
        assertEquals(
            listOf("$SERVICE $ACCOUNT"),
            service.collections.getValue(LOGIN_PATH).writtenLabels,
        )
        assertEquals(listOf(LOGIN_PATH), service.openedCollectionPaths.distinct())
        assertEquals(ROOT_PATH, service.defaultCollectionPath)

        store.delete()

        assertNull(store.read())
        assertEquals(emptyList(), service.collections.getValue(LOGIN_PATH).storedItems())
        assertEquals(ROOT_PATH, service.defaultCollectionPath)
    }

    @Test
    fun sessionDefaultUsesExplicitPersistentCollection() {
        val service = FakeReceiptSessionSecretService(
            defaultCollectionPath = SESSION_PATH,
            collectionPaths = listOf(SESSION_PATH, LOGIN_PATH),
        )

        KeyringReceiptSessionSecretStore({ service }) { true }.write("secret")

        assertEquals(listOf("secret"), service.collections.getValue(LOGIN_PATH).storedValues())
        assertEquals(emptyList(), service.collections.getValue(SESSION_PATH).storedValues())
        assertEquals(SESSION_PATH, service.defaultCollectionPath)
    }

    @Test
    fun existingPersistentDefaultIsChosenExplicitly() {
        val service = FakeReceiptSessionSecretService(
            defaultCollectionPath = CUSTOM_PATH,
            collectionPaths = listOf(LOGIN_PATH, CUSTOM_PATH),
        )

        KeyringReceiptSessionSecretStore({ service }) { true }.write("secret")

        assertEquals(listOf("secret"), service.collections.getValue(CUSTOM_PATH).storedValues())
        assertEquals(emptyList(), service.collections.getValue(LOGIN_PATH).storedValues())
        assertEquals(CUSTOM_PATH, service.defaultCollectionPath)
    }

    @Test
    fun existingAppItemStaysReachableAfterDefaultAliasChanges() {
        val service = FakeReceiptSessionSecretService(
            defaultCollectionPath = ROOT_PATH,
            collectionPaths = listOf(LOGIN_PATH, CUSTOM_PATH),
        )
        val store = KeyringReceiptSessionSecretStore({ service }) { true }

        store.write("first-secret")
        service.defaultCollectionPath = CUSTOM_PATH
        store.write("updated-secret")

        assertEquals("updated-secret", store.read())
        assertEquals(listOf("updated-secret"), service.collections.getValue(LOGIN_PATH).storedValues())
        assertEquals(emptyList(), service.collections.getValue(CUSTOM_PATH).storedValues())
        assertEquals(CUSTOM_PATH, service.defaultCollectionPath)
    }

    @Test
    fun rootOrSessionDefaultWithoutPersistentCollectionFailsClosed() {
        val rootDefaultService = FakeReceiptSessionSecretService(
            defaultCollectionPath = ROOT_PATH,
            collectionPaths = listOf(SESSION_PATH),
        )
        val sessionDefaultService = FakeReceiptSessionSecretService(
            defaultCollectionPath = SESSION_PATH,
            collectionPaths = listOf(SESSION_PATH),
        )

        assertFailsWith<ReceiptSessionSecretStoreUnavailableException> {
            KeyringReceiptSessionSecretStore({ rootDefaultService }) { true }.write("secret")
        }
        assertFailsWith<ReceiptSessionSecretStoreUnavailableException> {
            KeyringReceiptSessionSecretStore({ sessionDefaultService }) { true }.read()
        }
    }

    @Test
    fun rootDefaultPrefersLoginThenOtherPersistentCollection() {
        assertEquals(
            LOGIN_PATH,
            chooseReceiptPersistentCollection(
                defaultPath = ROOT_PATH,
                collectionPaths = listOf(SESSION_PATH, CUSTOM_PATH, LOGIN_PATH),
            ),
        )
        assertEquals(
            CUSTOM_PATH,
            chooseReceiptPersistentCollection(
                defaultPath = ROOT_PATH,
                collectionPaths = listOf(SESSION_PATH, CUSTOM_PATH),
            ),
        )
    }

    @Test
    fun currentPersistentDefaultWinsAndUnknownDefaultFallsBackExplicitly() {
        assertEquals(
            CUSTOM_PATH,
            chooseReceiptPersistentCollection(
                defaultPath = CUSTOM_PATH,
                collectionPaths = listOf(LOGIN_PATH, CUSTOM_PATH),
            ),
        )
        assertEquals(
            LOGIN_PATH,
            chooseReceiptPersistentCollection(
                defaultPath = "/org/freedesktop/secrets/collection/not-listed",
                collectionPaths = listOf(LOGIN_PATH),
            ),
        )
    }

    @Test
    fun missingPersistentCollectionFailsClosed() {
        assertFailsWith<ReceiptSessionSecretStoreUnavailableException> {
            chooseReceiptPersistentCollection(
                defaultPath = ROOT_PATH,
                collectionPaths = listOf(SESSION_PATH),
            )
        }
    }


    @Test
    fun concurrentFirstWritesUseAtomicCreateOrReplace() {
        val service = FakeReceiptSessionSecretService(
            defaultCollectionPath = ROOT_PATH,
            collectionPaths = listOf(LOGIN_PATH),
        )
        val collection = service.collections.getValue(LOGIN_PATH)
        collection.creationBarrier = CyclicBarrier(2)
        val stores = List(2) { KeyringReceiptSessionSecretStore({ service }) { true } }
        val writers = Executors.newFixedThreadPool(2)

        try {
            val first = writers.submit { stores[0].write("first-secret") }
            val second = writers.submit { stores[1].write("second-secret") }
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)
        } finally {
            writers.shutdownNow()
        }

        assertEquals(1, collection.storedItems().size)
        assertEquals(1, collection.storedValues().size)
    }

    @Test
    fun independentlyLockedItemsAreUnlockedBeforeReadUpdateAndDelete() {
        val service = FakeReceiptSessionSecretService(
            defaultCollectionPath = ROOT_PATH,
            collectionPaths = listOf(LOGIN_PATH),
        )
        val store = KeyringReceiptSessionSecretStore({ service }) { true }
        val collection = service.collections.getValue(LOGIN_PATH)
        store.write("initial-secret")
        val itemPath = collection.storedItems().single()
        collection.clearEvents()
        collection.promptPathForUnlock = PROMPT_PATH

        collection.lockItem(itemPath)
        assertEquals("initial-secret", store.read())
        assertEquals(
            listOf("unlock:$itemPath", "await:$PROMPT_PATH", "read:$itemPath"),
            collection.events(),
        )

        collection.clearEvents()
        collection.lockItem(itemPath)
        store.write("updated-secret")
        assertEquals(
            listOf("unlock:$itemPath", "await:$PROMPT_PATH", "write:$itemPath"),
            collection.events(),
        )

        collection.clearEvents()
        collection.lockItem(itemPath)
        store.delete()
        assertEquals(
            listOf("unlock:$itemPath", "await:$PROMPT_PATH", "delete:$itemPath"),
            collection.events(),
        )
        assertNull(store.read())
    }

    @Test
    fun dismissedOrIneffectiveItemUnlockFailsBeforeSecretOperation() {
        val service = FakeReceiptSessionSecretService(
            defaultCollectionPath = ROOT_PATH,
            collectionPaths = listOf(LOGIN_PATH),
        )
        val store = KeyringReceiptSessionSecretStore({ service }) { true }
        val collection = service.collections.getValue(LOGIN_PATH)
        store.write("initial-secret")
        val itemPath = collection.storedItems().single()
        collection.clearEvents()
        collection.lockItem(itemPath)
        collection.promptPathForUnlock = PROMPT_PATH
        collection.dismissUnlockPrompt = true

        assertFailsWith<ReceiptSessionSecretStoreUnavailableException> {
            store.read()
        }
        assertEquals(
            listOf("unlock:$itemPath", "await:$PROMPT_PATH"),
            collection.events(),
        )

        collection.clearEvents()
        collection.dismissUnlockPrompt = false
        collection.keepLockedAfterUnlock = true
        assertFailsWith<ReceiptSessionSecretStoreUnavailableException> {
            store.write("must-not-be-written")
        }
        assertEquals(
            listOf("unlock:$itemPath", "await:$PROMPT_PATH"),
            collection.events(),
        )
    }

    @Test
    fun lockedCollectionStillAwaitsPromptBeforeReading() {
        val service = FakeReceiptSessionSecretService(
            defaultCollectionPath = ROOT_PATH,
            collectionPaths = listOf(LOGIN_PATH),
        )
        val store = KeyringReceiptSessionSecretStore({ service }) { true }
        val collection = service.collections.getValue(LOGIN_PATH)
        store.write("initial-secret")
        val itemPath = collection.storedItems().single()
        collection.clearEvents()
        collection.lockCollection()
        collection.promptPathForUnlock = PROMPT_PATH

        assertEquals("initial-secret", store.read())
        assertEquals(
            listOf("unlock:$LOGIN_PATH", "await:$PROMPT_PATH", "read:$itemPath"),
            collection.events(),
        )
    }

    private companion object {
        const val ROOT_PATH = "/"
        const val LOGIN_PATH = "/org/freedesktop/secrets/collection/login"
        const val SESSION_PATH = "/org/freedesktop/secrets/collection/session"
        const val CUSTOM_PATH = "/org/freedesktop/secrets/collection/custom"
        const val PROMPT_PATH = "/org/freedesktop/secrets/prompt/p1"
        const val SERVICE = "smart-expense-agent.receipts"
        const val ACCOUNT = "real-session"
    }
}

private class FakeReceiptSessionSecretService(
    override var defaultCollectionPath: String,
    override val collectionPaths: List<String>,
) : ReceiptSessionSecretService {
    val openedCollectionPaths = mutableListOf<String>()
    val collections = collectionPaths.associateWith(::FakeReceiptSessionSecretCollection)

    override fun openCollection(path: String): ReceiptSessionSecretCollection {
        synchronized(openedCollectionPaths) {
            openedCollectionPaths += path
        }
        return collections.getValue(path)
    }

    override fun close() = Unit
}

private class FakeReceiptSessionSecretCollection(
    override val collectionPath: String,
) : ReceiptSessionSecretCollection {
    private val lock = Any()
    private val items = mutableListOf<FakeItem>()
    private val operations = mutableListOf<String>()
    private var nextItemId = 1
    private var collectionLocked = false
    private var pendingPromptObjectPath: String? = null
    val writtenAttributes = mutableListOf<Map<String, String>>()
    val writtenLabels = mutableListOf<String>()
    var creationBarrier: CyclicBarrier? = null
    var promptPathForUnlock = "/"
    var dismissUnlockPrompt = false
    var keepLockedAfterUnlock = false

    override fun isLocked(objectPath: String): Boolean = synchronized(lock) {
        if (objectPath == collectionPath) {
            collectionLocked
        } else {
            items.single { it.path == objectPath }.locked
        }
    }

    override fun unlock(objectPath: String): ReceiptSessionSecretUnlockResult = synchronized(lock) {
        operations += "unlock:$objectPath"
        if (promptPathForUnlock == "/") {
            if (!keepLockedAfterUnlock) setLocked(objectPath, false)
            ReceiptSessionSecretUnlockResult(
                unlockedObjectPaths = if (keepLockedAfterUnlock) emptyList() else listOf(objectPath),
                promptPath = "/",
            )
        } else {
            pendingPromptObjectPath = objectPath
            ReceiptSessionSecretUnlockResult(emptyList(), promptPathForUnlock)
        }
    }

    override fun awaitPrompt(promptPath: String): Boolean = synchronized(lock) {
        operations += "await:$promptPath"
        val objectPath = pendingPromptObjectPath
        pendingPromptObjectPath = null
        if (dismissUnlockPrompt) {
            false
        } else {
            if (!keepLockedAfterUnlock && objectPath != null) setLocked(objectPath, false)
            true
        }
    }

    override fun findItems(attributes: Map<String, String>): List<String> =
        synchronized(lock) {
            items.filter { item -> attributes.all { (key, value) -> item.attributes[key] == value } }
                .map(FakeItem::path)
        }

    override fun read(itemPath: String): String = synchronized(lock) {
        val item = items.single { it.path == itemPath }
        check(!item.locked)
        operations += "read:$itemPath"
        item.value
    }

    override fun write(
        itemPath: String?,
        label: String,
        attributes: Map<String, String>,
        replaceExisting: Boolean,
        value: String,
    ) {
        if (itemPath == null) {
            creationBarrier?.await(10, TimeUnit.SECONDS)
        }
        synchronized(lock) {
            if (itemPath == null) {
                if (replaceExisting) {
                    items.removeAll { item ->
                        attributes.all { (key, attributeValue) -> item.attributes[key] == attributeValue }
                    }
                }
                items += FakeItem("item-${nextItemId++}", label, attributes.toMap(), value)
                operations += "create"
            } else {
                val item = items.single { it.path == itemPath }
                check(item.attributes == attributes)
                check(item.label == label)
                check(!item.locked)
                item.value = value
                operations += "write:$itemPath"
            }
            writtenAttributes += attributes
            writtenLabels += label
        }
    }

    override fun delete(itemPath: String, attributes: Map<String, String>) {
        synchronized(lock) {
            val item = items.single { it.path == itemPath }
            check(item.attributes == attributes)
            check(!item.locked)
            items.remove(item)
            operations += "delete:$itemPath"
        }
    }

    fun lockCollection() {
        synchronized(lock) {
            collectionLocked = true
        }
    }

    fun lockItem(itemPath: String) {
        synchronized(lock) {
            items.single { it.path == itemPath }.locked = true
        }
    }

    fun events(): List<String> = synchronized(lock) { operations.toList() }

    fun clearEvents() {
        synchronized(lock) {
            operations.clear()
        }
    }

    fun storedItems(): List<String> = synchronized(lock) { items.map(FakeItem::path) }

    fun storedValues(): List<String> = synchronized(lock) { items.map(FakeItem::value) }

    private fun setLocked(objectPath: String, locked: Boolean) {
        if (objectPath == collectionPath) {
            collectionLocked = locked
        } else {
            items.single { it.path == objectPath }.locked = locked
        }
    }

    private data class FakeItem(
        val path: String,
        val label: String,
        val attributes: Map<String, String>,
        var value: String,
        var locked: Boolean = false,
    )
}
