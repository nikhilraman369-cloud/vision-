package ai.nexus.core.nlu

/** Well-known apps and the names people use for them. */
object BuiltInApps {
    /** alias → package */
    val aliases: Map<String, String> = buildMap {
        fun add(pkg: String, vararg names: String) { for (n in names) put(n, pkg) }
        add("com.google.android.youtube", "youtube", "yt", "you tube")
        add("com.instagram.android", "instagram", "insta", "ig")
        add("com.whatsapp", "whatsapp", "whats app", "wa")
        add("com.whatsapp.w4b", "whatsapp business")
        add("com.android.chrome", "chrome", "google chrome", "browser", "internet")
        add("com.google.android.googlequicksearchbox", "google", "google app")
        add("com.google.android.gm", "gmail", "email", "mail", "e-mail")
        add("com.spotify.music", "spotify")
        add("com.google.android.apps.youtube.music", "youtube music", "yt music")
        add("com.google.android.apps.maps", "maps", "google maps")
        add("com.android.settings", "settings", "phone settings", "system settings")
        add("com.google.android.dialer", "phone", "dialer", "dialler", "phone app", "calls")
        add("com.google.android.apps.messaging", "messages", "sms", "text messages", "messaging")
        add("com.google.android.contacts", "contacts")
        add("com.google.android.apps.photos", "photos", "google photos", "gallery")
        add("com.google.android.GoogleCamera", "camera")
        add("com.google.android.calendar", "calendar")
        add("com.google.android.deskclock", "clock", "alarm", "alarms", "timer")
        add("com.google.android.calculator", "calculator")
        add("com.google.android.apps.nbu.files", "files", "file manager", "my files", "downloads")
        add("com.android.vending", "play store", "google play", "playstore")
        add("com.facebook.katana", "facebook", "fb")
        add("com.facebook.orca", "messenger")
        add("org.telegram.messenger", "telegram")
        add("com.twitter.android", "twitter", "x")
        add("com.snapchat.android", "snapchat", "snap")
        add("com.linkedin.android", "linkedin")
        add("com.netflix.mediaclient", "netflix")
        add("in.amazon.mShop.android.shopping", "amazon")
        add("com.flipkart.android", "flipkart")
        add("com.phonepe.app", "phonepe", "phone pe")
        add("net.one97.paytm", "paytm")
        add("com.google.android.apps.nbu.paisa.user", "gpay", "google pay")
        add("com.zomato.android", "zomato")
        add("in.swiggy.android", "swiggy")
        add("com.ubercab", "uber")
        add("com.olacabs.customer", "ola")
        add("com.google.android.keep", "keep", "google keep", "notes")
        add("com.google.android.apps.docs", "drive", "google drive")
        add("com.google.android.apps.docs.editors.docs", "docs", "google docs")
        add("com.microsoft.teams", "teams")
        add("us.zoom.videomeetings", "zoom")
        add("com.google.android.apps.meetings", "meet", "google meet")
        add("com.jio.media.jiobeats", "jiosaavn", "saavn")
        add("com.hotstar.android", "hotstar", "disney hotstar", "jiohotstar")
        add("com.truecaller", "truecaller")
        add("com.discord", "discord")
        add("com.reddit.frontpage", "reddit")
        add("com.pinterest", "pinterest")
        add("ai.nexus.app", "nexus")
    }

    fun isKnown(name: String): Boolean = aliases.containsKey(Fuzzy.normalize(name))

    fun packageFor(name: String): String? = aliases[Fuzzy.normalize(name)]
}
