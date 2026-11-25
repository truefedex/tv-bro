package com.phlox.tvwebbrowser.webengine

/** A profile whose pages keep their data (cookies, storage, cache) apart in the engine */
enum class DataProfile {
    MAIN,
    KIDS,
    INCOGNITO
}

/** How [WebEngineProviderCallback.clearBrowsingData] can clear the data of a profile */
enum class DataClearing {
    /** Its data alone */
    SEPARATE,

    /** Only together with the other profiles cleared [WITH_OTHERS]: one call clears all of them */
    WITH_OTHERS,

    /** Not at all (they go when the profile ends, as those of the incognito mode) */
    NONE
}
