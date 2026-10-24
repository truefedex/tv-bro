package com.phlox.tvwebbrowser.browser.adblock

/** A filter list the app knows: [languages] (ISO 639) of the sites of a regional list, empty for the others */
data class FilterListInfo(val id: String, val title: String, val url: String, val languages: Set<String> = emptySet()) {
    val isRegional: Boolean get() = languages.isNotEmpty()
}

/**
 * The filter lists of the settings: EasyList and EasyPrivacy, one regional list (for the language of the
 * system by default) and the user's own list ([CUSTOM], by its address).
 */
object FilterListCatalog {
    const val EASYLIST = "easylist"
    const val EASYPRIVACY = "easyprivacy"
    /** The list the user gives the address of */
    const val CUSTOM = "custom"

    private const val ABP = "https://easylist-downloads.adblockplus.org"

    val lists = listOf(
        FilterListInfo(EASYLIST, "EasyList", "https://easylist.to/easylist/easylist.txt"),
        FilterListInfo(EASYPRIVACY, "EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt"),
        FilterListInfo("ruadlist", "RU AdList", "$ABP/advblock.txt", setOf("ru", "uk", "be", "kk", "uz")),
        FilterListInfo("easylistgermany", "EasyList Germany", "https://easylist.to/easylistgermany/easylistgermany.txt",
            setOf("de")),
        FilterListInfo("listefr", "Liste FR", "$ABP/liste_fr.txt", setOf("fr")),
        FilterListInfo("easylistspanish", "EasyList Spanish", "$ABP/easylistspanish.txt", setOf("es")),
        FilterListInfo("easylistportuguese", "EasyList Portuguese", "$ABP/easylistportuguese.txt", setOf("pt")),
        FilterListInfo("easylistitaly", "EasyList Italy", "$ABP/easylistitaly.txt", setOf("it")),
        FilterListInfo("easylistdutch", "EasyList Dutch", "$ABP/easylistdutch.txt", setOf("nl")),
        FilterListInfo("easylistpolish", "EasyList Polish", "$ABP/easylistpolish.txt", setOf("pl")),
        FilterListInfo("easylistczechslovak", "EasyList Czech and Slovak", "$ABP/easylistczechslovak.txt", setOf("cs", "sk")),
        FilterListInfo("bulgarianlist", "Bulgarian list", "$ABP/bulgarian_list.txt", setOf("bg")),
        FilterListInfo("easylistlithuania", "EasyList Lithuania", "$ABP/easylistlithuania.txt", setOf("lt")),
        FilterListInfo("latvianlist", "Latvian List", "$ABP/latvianlist.txt", setOf("lv")),
        FilterListInfo("easylisthebrew", "EasyList Hebrew", "$ABP/israellist.txt", setOf("he", "iw")),
        FilterListInfo("listear", "Liste AR", "$ABP/Liste_AR.txt", setOf("ar")),
        FilterListInfo("easylistchina", "EasyList China", "$ABP/easylistchina.txt", setOf("zh")),
        FilterListInfo("koreanlist", "KoreanList", "$ABP/koreanlist.txt", setOf("ko")),
        FilterListInfo("abpvn", "ABPVN List", "$ABP/abpvn.txt", setOf("vi")),
        FilterListInfo("abpindo", "ABPindo", "$ABP/abpindo.txt", setOf("id", "in")),
        FilterListInfo("indianlist", "IndianList", "$ABP/indianlist.txt",
            setOf("hi", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa")),
    )

    val regional: List<FilterListInfo> get() = lists.filter { it.isRegional }

    fun find(id: String): FilterListInfo? = lists.firstOrNull { it.id == id }

    /** The lists turned on when the user has chosen none yet: EasyList, EasyPrivacy and the list of the [language] */
    fun defaults(language: String): Set<String> =
        setOfNotNull(EASYLIST, EASYPRIVACY, regional.firstOrNull { language in it.languages }?.id)
}
