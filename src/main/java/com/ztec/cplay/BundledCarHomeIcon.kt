package com.ztec.cplay

/**
 * Bundled CarPlay "return to car" icons. BYD is intentionally not included.
 */
enum class BundledCarHomeIcon(val rawResName: String) {
    NEXUS("ic_car_home"),
    HONGQI("ic_brand_hongqi"),
    GEELY("ic_brand_geely"),
    NETA("ic_brand_neta"),
    TOYOTA("ic_brand_toyota"),
    ;

    companion object {
        fun fromStored(name: String?): BundledCarHomeIcon =
            entries.firstOrNull { it.name == name } ?: NEXUS
    }
}
