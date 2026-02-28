# DK Repo - CloudStream Extensions

A CloudStream 3 extensions repository containing custom streaming providers.

## Plugins

| Plugin | Version | Language | Status |
|--------|---------|----------|--------|
| VidFastProvider | 1 | 🇬🇧 en | ✅ Working |

## Installation

1. Open **CloudStream 3**
2. Go to **Settings → Extensions → Add Repository**
3. Enter the repository URL:
   ```
   https://raw.githubusercontent.com/donadhi-kamesh/dk-repo/builds/repo.json
   ```
   *Alternative:* In a browser on your phone, you can directly tap this link:
   `cloudstreamrepo://raw.githubusercontent.com/donadhi-kamesh/dk-repo/builds/repo.json`
4. Install desired extensions

## Providers

### VidFastProvider
Streams movies and TV shows using VidFast (vidfast.pro).
- Powered by TMDB for search & metadata
- Supports Movies and TV Series
- Multiple quality options (HD, 4K when available)
- Multi-language subtitles support

## Building

Requirements:
- JDK 17+
- Android SDK

```bash
./gradlew make makePluginsJson
```

## Credits
- Based on the [Cloudstream extensions](https://github.com/recloudstream/extensions) template
- VidFast provider powered by [vidfast.net](https://www.vidfast.net/)
