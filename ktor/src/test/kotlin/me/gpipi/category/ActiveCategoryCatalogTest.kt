package me.gpipi.category

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class ActiveCategoryCatalogTest {
    private fun category(
        name: String,
        description: String,
    ) = CategoryRow(
        id = UUID.randomUUID(),
        name = name,
        description = description,
    )

    @Test
    fun `concurrent cold reads share one generation load`() = runBlocking {
        val calls = AtomicInteger()
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val expected = listOf(category("Eating Out", "Restaurants and cafes"))
        val catalog = ActiveCategoryCatalog {
            calls.incrementAndGet()
            loadStarted.complete(Unit)
            releaseLoad.await()
            expected
        }

        val reads = List(20) {
            async { catalog.current() }
        }
        loadStarted.await()
        releaseLoad.complete(Unit)

        assertEquals(List(20) { expected }, reads.awaitAll())
        assertEquals(1, calls.get())
    }

    @Test
    fun `advance eagerly rebuilds one new generation`() = runBlocking {
        val calls = AtomicInteger()
        val rows = AtomicReference(
            listOf(category("Eating Out", "Old description")),
        )
        val catalog = ActiveCategoryCatalog {
            calls.incrementAndGet()
            rows.get()
        }

        assertEquals("Old description", catalog.current().single().description)

        rows.set(listOf(category("Eating Out", "New description")))
        catalog.advanceAndRebuild()

        assertEquals("New description", catalog.current().single().description)
        assertEquals(2, calls.get())
    }

    @Test
    fun `readers join the eager rebuild for an advanced generation`() = runBlocking {
        val calls = AtomicInteger()
        val rebuildStarted = CompletableDeferred<Unit>()
        val releaseRebuild = CompletableDeferred<Unit>()
        val oldRows = listOf(category("Eating Out", "Old description"))
        val newRows = listOf(category("Eating Out", "New description"))
        val catalog = ActiveCategoryCatalog {
            when (calls.incrementAndGet()) {
                1 -> oldRows
                else -> {
                    rebuildStarted.complete(Unit)
                    releaseRebuild.await()
                    newRows
                }
            }
        }

        assertEquals(oldRows, catalog.current())

        val rebuild = async { catalog.advanceAndRebuild() }
        rebuildStarted.await()
        val reads = List(20) {
            async { catalog.current() }
        }
        releaseRebuild.complete(Unit)

        rebuild.await()
        assertEquals(List(20) { newRows }, reads.awaitAll())
        assertEquals(2, calls.get())
    }

    @Test
    fun `read retries when its in-flight generation is superseded`() = runBlocking {
        val calls = AtomicInteger()
        val oldLoadStarted = CompletableDeferred<Unit>()
        val releaseOldLoad = CompletableDeferred<Unit>()
        val oldRows = listOf(category("Eating Out", "Old description"))
        val newRows = listOf(category("Eating Out", "New description"))
        val catalog = ActiveCategoryCatalog {
            when (calls.incrementAndGet()) {
                1 -> {
                    oldLoadStarted.complete(Unit)
                    releaseOldLoad.await()
                    oldRows
                }

                else -> newRows
            }
        }

        val read = async { catalog.current() }
        oldLoadStarted.await()

        catalog.advanceAndRebuild()
        releaseOldLoad.complete(Unit)

        assertEquals(newRows, read.await())
        assertEquals(2, calls.get())
    }

    @Test
    fun `failed eager rebuild is retried by the next read`() = runBlocking {
        val calls = AtomicInteger()
        val expected = listOf(category("Transport", "Trains and buses"))
        val catalog = ActiveCategoryCatalog {
            if (calls.incrementAndGet() == 1) {
                error("temporary database failure")
            }
            expected
        }

        catalog.advanceAndRebuild()

        assertEquals(expected, catalog.current())
        assertEquals(2, calls.get())
    }
}
