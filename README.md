This tool can be used to reverse a Spotify playlist automatically. Usage:

```
Usage: spotify-playlist-reverser-kt options_list
Options:
    --client-id -> API client ID (always required) { String }
    --client-secret -> API client secret (always required) { String }
    --source-playlist-id -> ID of the source playlist (always required) { String }
    --target-playlist-name -> Name of the target playlist. If it already exists, specify --override-existing. Defaults to "${sourcePlaylist.name} (reversed)" { String }    
    --target-playlist-description -> If the target playlist does not exist yet, this will be used as its description. Defaults to an empty value. { String }
    --override-existing [false] -> If a playlist with the same target name already exists, override it.
    --help, -h -> Usage info
```

On first use, you'll have to authorize the tool through the URL it prints. In case you're running the tool on a system without a browser, just open the URL in another browser and copy the `localhost`-URL from your browser after authorization. Then open a new terminal, connect to the system without the browser, and finally run `curl <the-localhost-URL>`.
On subsequent uses, the tool will use cached credentials (saved in `.spotify-token.txt`).

Binaries are available in the [releases section](https://github.com/cbruegg/spotify-playlist-reverser-kt/releases). This includes x64 builds for Windows, Mac and Linux, as well as a platform-independent Java jar.
