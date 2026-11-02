# Covers

## Does Kavita use `cover.png` in a series folder?

No, not as of Kavita 0.9.1.6 (checked September 2026):

- The [scanner documentation](https://wiki.kavitareader.com/guides/scanner/) says image files named
  `cover`/`folder` are treated as a cover image *and excluded from parsing*, meaning they are not
  read as a page. It does not say they become the series cover.
- In the source, the only place that uses such a file as a cover is `ArchiveService.FindFolderEntry`,
  which looks **inside a CBZ**. The series cover itself comes from its first volume/chapter
  (`series.GetCoverImage()` in `MetadataService.UpdateSeriesCoverImage`). The scanner parsers only use
  `Parser.IsCoverImage` to *skip* loose cover files.
- Using a folder `cover.png` as the series cover is an open feature request:
  [Kavita discussion #3086](https://github.com/Kareadita/Kavita/discussions/3086).
- In 0.9.1 a loose `cover.png` even shows up under *Media Issues*
  ([issue #4906](https://github.com/Kareadita/Kavita/issues/4906)).

## What the service does instead

Kavita 0.9.1 added a two-step upload that avoids sending the image as base64:

1. `POST /api/Upload/upload-by-file` with the PNG as a normal multipart file. Kavita stores it in its
   temp folder and returns a file name.
2. `POST /api/Upload/series` with `{"id": <series>, "fileName": "<that name>", "lockCover": true}`.

Kavita's own code calls this the preferred path and base64 the legacy fallback. The service uses it
automatically and falls back to base64 once (and remembers) if Kavita answers 404/405.

Which series get a cover: every series in a configured library whose cover is **not locked** and that
matches a Suwayomi manga. After upload the cover is locked, so it's done once. Covers you uploaded
yourself in Kavita are locked too and are never touched.

To refresh a cover later, unlock it in Kavita (*Edit series > Cover > reset*) and the next run sets it
again from Suwayomi.

## Options

```yaml
covers:
  enabled: true            # turn the whole cover step off with false
  lock: true               # false = leave covers unlocked; uploads are then tracked in
                           # data/history.db and repeated only when the Suwayomi image changes
  write_cover_file: false  # also write cover.png into each series folder
  file_name: cover.png
```

`write_cover_file` is there if you want the file for another reader (Komga, for example, does use it)
or for a future Kavita release that adds the feature. It never overwrites an existing `cover.*` file.
