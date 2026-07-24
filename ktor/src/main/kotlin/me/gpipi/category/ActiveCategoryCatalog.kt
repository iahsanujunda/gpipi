package me.gpipi.category

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.asCache
import com.sksamuel.aedile.core.expireAfterWrite
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import me.gpipi.config.dbQuery
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory

fun interface ActiveCategoryReader {
    suspend fun current(): List<CategoryRow>
}

fun interface ActiveCategoryRebuilder {
    suspend fun advanceAndRebuild()
}

class ActiveCategoryCatalog internal constructor(
    private val loadCategories: suspend () -> List<CategoryRow>,
) : ActiveCategoryReader, ActiveCategoryRebuilder {
    constructor(
        db: Database,
        categoryRepo: CategoryRepository,
    ) : this(
        loadCategories = {
            dbQuery(db) { categoryRepo.findActive() }
        },
    )

    private val log = LoggerFactory.getLogger(ActiveCategoryCatalog::class.java)
    private val generation = AtomicLong(0)

    private val cache = Caffeine
        .newBuilder()
        .maximumSize(4)
        .expireAfterWrite(5.minutes)
        .asCache<Long, List<CategoryRow>>()

    private suspend fun load(generationKey: Long): List<CategoryRow> =
        cache.get(generationKey) { loadCategories() }

    override suspend fun current(): List<CategoryRow> {
        while (true) {
            val observedGeneration = generation.get()
            val categories = load(observedGeneration)

            if (generation.get() == observedGeneration) {
                return categories
            }
        }
    }

    override suspend fun advanceAndRebuild() {
        val nextGeneration = generation.incrementAndGet()

        try {
            load(nextGeneration)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.warn(
                "Could not eagerly rebuild active categories for generation {}; the next read will retry",
                nextGeneration,
                ex,
            )
        }
    }
}
