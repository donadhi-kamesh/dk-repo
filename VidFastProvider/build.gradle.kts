// Use an integer for version numbers
version = 1

cloudstream {
    language = "en"
    description = "Watch Movies & TV Shows via VidFast (vidfast.pro)"
    authors = listOf("DK")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=vidfast.pro&sz=%size%"
}
