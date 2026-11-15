/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.media

import android.content.ContentUris
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.AlbumArtistMap
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.withDatabaseTransaction
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

internal class MediaStoreAudioLibrary(
    private val context: Context,
    private val database: MusicDatabase,
) {
    private val resolver = context.contentResolver

    fun hasReadPermission(): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun refresh() {
        check(hasReadPermission()) { "Audio library permission has not been granted" }
        val tracks = queryTracks()
        val dao = database.dao()
        val ids = tracks.map { it.uri }
        val existingSongs =
            ids
                .chunked(900)
                .flatMap { dao.songEntitiesByIds(it) }
                .associateBy { it.id }
        val staleIds = dao.mediaStoreSongIds().toSet() - ids.toSet()
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val artists =
            tracks
                .flatMap { it.artist?.let(::listOf).orEmpty() }
                .distinctBy { it.id }
                .associateBy { it.id }
        val albums =
            tracks
                .mapNotNull { track -> track.album?.let { it.id to track } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (id, albumTracks) ->
                    val sample = requireNotNull(albumTracks.first().album)
                    id to
                        sample.copy(
                            songCount = albumTracks.size,
                            duration = albumTracks.sumOf { (it.durationMs ?: 0L) / 1_000L }.toInt(),
                        )
                }.values
                .associate { it }

        database.withDatabaseTransaction {
            staleIds.chunked(900).forEach { dao.deleteMediaStoreSongs(it) }
            ids.chunked(900).forEach { chunk ->
                dao.deleteSongArtistMappings(chunk)
                dao.deleteSongAlbumMappings(chunk)
            }
            dao.upsertArtistEntities(artists.values.toList())
            dao.upsertAlbumEntities(albums.values.toList())
            if (tracks.isNotEmpty()) dao.upsertSongEntities(
                tracks.map { track ->
                    val old = existingSongs[track.uri]
                    old?.copy(
                        title = track.title,
                        duration = track.durationMs?.div(1_000L)?.toInt() ?: -1,
                        thumbnailUrl = track.thumbnail,
                        albumId = track.album?.id,
                        albumName = track.album?.title,
                        year = track.year,
                        inLibrary = old.inLibrary ?: now,
                        isLocal = true,
                    ) ?: SongEntity(
                        id = track.uri,
                        title = track.title,
                        duration = track.durationMs?.div(1_000L)?.toInt() ?: -1,
                        thumbnailUrl = track.thumbnail,
                        albumId = track.album?.id,
                        albumName = track.album?.title,
                        year = track.year,
                        inLibrary = now,
                        isLocal = true,
                    )
                },
            )
            if (tracks.isNotEmpty()) dao.upsertSongArtists(
                tracks.mapNotNull { track ->
                    track.artist?.let { SongArtistMap(songId = track.uri, artistId = it.id, position = 0) }
                },
            )
            if (tracks.isNotEmpty()) dao.upsertSongAlbums(
                tracks.mapNotNull { track ->
                    track.album?.let { SongAlbumMap(songId = track.uri, albumId = it.id, index = track.trackNumber ?: 0) }
                },
            )
            if (tracks.isNotEmpty()) dao.upsertAlbumArtists(
                tracks.mapNotNull { track ->
                    val artist = track.artist
                    val album = track.album
                    if (artist == null || album == null) {
                        null
                    } else {
                        AlbumArtistMap(albumId = album.id, artistId = artist.id, order = 0)
                    }
                }.distinct(),
            )
        }
    }

    private fun queryTracks(): List<MediaTrack> {
        val projection =
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.MIME_TYPE,
            )
        val tracks = mutableListOf<MediaTrack>()
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val displayNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val yearColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(displayNameColumn).orEmpty()
                val mimeType = cursor.getString(mimeTypeColumn)
                if (!isSupportedAudioFile(mimeType, displayName)) continue
                val mediaId = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId).toString()
                val title =
                    cursor.getString(titleColumn)
                        ?.takeIf(String::isNotBlank)
                        ?: displayName.substringBeforeLast('.', displayName)
                val artistName = cursor.getString(artistColumn)?.takeIf(String::isNotBlank)
                val albumName = cursor.getString(albumColumn)?.takeIf(String::isNotBlank)
                val albumId = cursor.getLong(albumIdColumn).takeIf { albumName != null && it > 0L }
                val artist =
                    artistName?.let { name ->
                        ArtistEntity(id = "local-artist:${name.lowercase()}", name = name, isLocal = true)
                    }
                val album =
                    albumId?.let { id ->
                        val albumArtwork = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, id)
                        AlbumEntity(
                            id = "local-album:$id",
                            title = albumName.orEmpty(),
                            year = cursor.getInt(yearColumn).takeIf { it > 0 },
                            thumbnailUrl = albumArtwork.toString(),
                            songCount = 0,
                            duration = 0,
                            isLocal = true,
                        )
                    }
                tracks +=
                    MediaTrack(
                        uri = uri,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = cursor.getLong(durationColumn).takeIf { !cursor.isNull(durationColumn) && it > 0L },
                        year = cursor.getInt(yearColumn).takeIf { !cursor.isNull(yearColumn) && it > 0 },
                        trackNumber = cursor.getInt(trackColumn).takeIf { !cursor.isNull(trackColumn) && it > 0 }?.rem(1_000),
                        thumbnail = album?.thumbnailUrl,
                    )
            }
        } ?: error("Unable to query MediaStore audio")
        return tracks
    }
}

internal fun isSupportedAudioFile(
    mimeType: String?,
    displayName: String,
): Boolean {
    val supportedMimeTypes =
        setOf(
            "audio/flac",
            "audio/x-flac",
            "audio/wav",
            "audio/x-wav",
            "audio/wave",
            "audio/mp4",
            "audio/x-m4a",
            "audio/mpeg",
        )
    val supportedExtensions = setOf("flac", "wav", "m4a", "mp3")
    return mimeType?.lowercase() in supportedMimeTypes ||
        displayName.substringAfterLast('.', "").lowercase() in supportedExtensions
}

private data class MediaTrack(
    val uri: String,
    val title: String,
    val artist: ArtistEntity?,
    val album: AlbumEntity?,
    val durationMs: Long?,
    val year: Int?,
    val trackNumber: Int?,
    val thumbnail: String?,
)
