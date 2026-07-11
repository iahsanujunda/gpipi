package me.gpipi.category

import java.util.UUID
import me.gpipi.generated.db.base.public1.Category
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

data class CategoryRow(
    val id: UUID,
    val name: String,
    val description: String,
)

class CategoryRepository {
    fun findActive(): List<CategoryRow> =
        Category
            .select(Category.id, Category.name, Category.description)
            .where { Category.active eq true }
            .map { row ->
                CategoryRow(
                    id = row[Category.id],
                    name = row[Category.name],
                    description = row[Category.description]
                )
            }
}