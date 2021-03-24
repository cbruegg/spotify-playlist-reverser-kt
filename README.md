This tool can be used to reverse a Spotify playlist automatically. Usage:

```
Usage: spotify-playlist-reverser-kt options_list
Options:
    --client-id -> API client ID (always required) { String }
    --client-secret -> API client secret (always required) { String }
    --source-playlist-id -> ID of the source playlist (always required) { String }
    --target-playlist-name -> Name of the target playlist. If it already exists, specify --override-existing. { String }    --target-playlist-description -> If the target playlist does not exist yet, this will be used as its description. { String }
    --override-existing [false] -> If a playlist with the same target name already exists, override it.
    --help, -h -> Usage info
```
