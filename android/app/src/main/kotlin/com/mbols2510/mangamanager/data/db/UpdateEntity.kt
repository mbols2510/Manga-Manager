package com.mbols2510.mangamanager.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mbols2510.mangamanager.data.api.UpdateDto

/**
 * A cached update. The primary key is the server's stable update id, so a re-delivered item is an upsert,
 * never a duplicate. [chapters] is stored as a JSON array ([Converters]). [notified] is local state (ruling 21):
 * false means "stored, notification decision not taken yet"; true once a notification was posted for this id, quiet
 * hours suppressed it, or notifications were unavailable.
 */
@Entity(tableName = "updates", indices = [Index(value = ["createdAt"])])
data class UpdateEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val category: String,
    val isNew: Boolean,
    val chapters: List<String>,
    val createdAt: String,
    val thumbnail: String?,
    val kavitaUrl: String?,
    val sensitive: Boolean,
    val notified: Boolean = false,
)

fun UpdateDto.toEntity(): UpdateEntity = UpdateEntity(
    id = id,
    title = title,
    category = category.orEmpty(),
    isNew = isNew,
    chapters = chapters,
    createdAt = createdAt.orEmpty(),
    thumbnail = thumbnail,
    kavitaUrl = kavitaUrl,
    sensitive = sensitive ?: false,
)
