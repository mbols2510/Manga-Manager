package com.mbols2510.mangamanager.notify

import com.mbols2510.mangamanager.data.db.UpdateEntity

/** A stored update for notification tests. */
fun update(
    id: Long,
    title: String = "Series $id",
    chapters: List<String> = listOf("Ch. $id"),
    isNew: Boolean = false,
    sensitive: Boolean = false,
    thumbnail: String? = "thumb/$id.png",
) = UpdateEntity(
    id = id,
    title = title,
    category = if (sensitive) "Mature" else "Manga",
    isNew = isNew,
    chapters = chapters,
    createdAt = "2026-11-07T10:00:00+00:00",
    thumbnail = thumbnail,
    kavitaUrl = "https://kavita.example/s/$id",
    sensitive = sensitive,
)
