package org.darkan.core.name

/**
 * A term belongs in [inWord] only when no ordinary word contains it, because [inWord] matches
 * anywhere inside a word; anything that turns up inside an innocent word ("ass" in "assassin",
 * "coon" in "raccoon") goes in [wholeWord], which matches a whole word and nothing else.
 * [allowed] rescues the few real words that do contain an [inWord] term.
 */
internal object ProfanityTerms {

    val inWord: Set<String> = setOf(
        "fuck", "phuck", "motherfuck", "clusterfuck",
        "shit", "bullshit", "shithead", "dipshit", "shitface",
        "cunt", "bitch", "bastard", "whore", "hoebag", "skank",
        "asshole", "arsehole", "assfuck", "buttfuck", "buttplug", "buttlick", "dumbass", "jackass",
        "penis", "vagina", "testicle", "scrotum", "smegma", "rectum", "sodomy", "sodomi",
        "ejaculat", "masturbat", "orgasm", "clitoris", "foreskin", "prolapse",
        "blowjob", "handjob", "rimjob", "footjob", "cumshot", "cumslut", "creampie", "gangbang",
        "bukkake", "deepthroat", "fellatio", "cunnilingus", "felching", "queef",
        "dildo", "fleshlight", "cockring",
        "cocksuck", "dicksuck", "dickhead", "dicklet", "cockhead",
        "jackoff", "jerkoff", "wanker", "wanking",
        "pussy", "titties", "titty", "boobies",
        "porno", "pornhub", "xhamster", "onlyfans",
        "hentai", "futanari", "ahegao", "lolicon", "shotacon", "lolita",
        "bestiality", "zoophil", "necrophil", "coprophil", "scatophil", "yiffing",
        "incest", "molest", "pedophil", "paedophil", "pedofil", "jailbait", "childporn",
        "rapist", "gangrap",
        "milf", "dilf", "bdsm", "fisting",
        "nigger", "nigga", "niglet", "wigger", "jigaboo", "porchmonkey", "tarbaby",
        "sandnigger", "towelhead", "wetback", "beaner", "halfbreed",
        "faggot", "fagot",
        "tranny", "trannie", "shemale", "ladyboy",
        "retard", "mongoloid", "windowlick",
        "nazi", "hitler", "siegheil", "heilhitler", "whitepower", "swastika", "holocaust",
        "jihad", "alqaeda", "goatse", "lemonparty",
    )

    val wholeWord: Set<String> = setOf(
        "ass", "arse", "anal", "anus", "butthole",
        "cum", "cums", "jizz", "semen", "schlong",
        "cock", "cocks", "dick", "dicks",
        "tit", "tits", "boob", "boobs", "twat", "minge", "bollock", "bollocks",
        "wank", "wanks", "slut", "sluts", "smut", "piss", "pissed", "turd",
        "porn", "porns",
        "rape", "raped", "rapes", "raping", "rapings", "mulatto", "mulattos", "mulattoes",
        "pedo", "pedos", "paedo", "loli", "futa", "shota", "vore", "scat",
        "fag", "fags", "coon", "coons", "kike", "kikes", "spic", "spics", "chink", "chinks",
        "gook", "gooks", "wop", "wops", "dago", "paki", "pakis", "abo", "abos", "negro", "negroes",
        "negros", "dyke", "dykes", "tard", "tards", "spastic", "spaz", "midget",
        "kkk", "heil", "reich", "gestapo",
    )

    val allowed: Set<String> = setOf(
        "scunthorpe", "penistone",
        "shiitake", "shitake", "shiitakes",
        "fagotto", "fagotti",
        "nazionale", "nazir", "nazim", "naziha",
        "therapist", "therapists", "therapy",
        "scraping", "scrapings", "draping",
        "retardant", "retardants", "retarder", "retarders", "retarding",
        "annal", "annals", "conn", "conns", "shitepoke",
        "rapping", "rappings", "rapper", "rappers",
        "niggard", "niggards", "niggardly",
        "snigger", "sniggers", "sniggered", "sniggering",
        "pussycat", "pussycats",
    )

    /** Names that pretend to be staff, refused exactly the way a taken name is. */
    val impersonation: Set<String> = setOf(
        "mod", "mods", "jmod", "jmods", "jagex", "admin", "admins", "administrator",
        "moderator", "owner", "staff", "system", "darkan", "support", "console",
    )
}
