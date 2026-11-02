package com.kaya.health.data

/**
 * A built-in list of common foods with a typical home portion, so logging is a search and a tap.
 * Values are approximate per portion (Indian Food Composition Tables 2017 / USDA, rounded); home cooking
 * varies with oil and portion size, so treat them as good estimates. Works fully offline.
 */
data class FoodItem(
    val name: String,
    /** Typical portion, e.g. "1 cup (150 g)". */
    val portion: String,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val fiber: Double,
    /** Extra words people search with (Hindi / Telugu / Tamil names, spellings). */
    val aka: String = "",
    val group: String,
)

object FoodDb {
    /** group, name, portion, kcal, protein g, carbs g, fat g, fiber g, other names */
    private fun f(group: String, name: String, portion: String, kcal: Int, p: Double, c: Double, fat: Double, fib: Double, aka: String = "") =
        FoodItem(name, portion, kcal.toDouble(), p, c, fat, fib, aka, group)

    val ALL: List<FoodItem> = listOf(
        // Breads and breakfast
        f("Breads & breakfast", "Roti / phulka", "1 medium (40 g)", 110, 3.5, 22.0, 1.0, 3.0, "chapati chapathi fulka"),
        f("Breads & breakfast", "Roti with ghee", "1 medium", 150, 3.5, 22.0, 5.5, 3.0, "chapati ghee"),
        f("Breads & breakfast", "Paratha, plain", "1 (80 g)", 260, 5.0, 34.0, 11.5, 4.0, "parantha"),
        f("Breads & breakfast", "Aloo paratha", "1 (120 g)", 300, 6.0, 42.0, 12.0, 4.0, "potato paratha"),
        f("Breads & breakfast", "Puri", "1 (25 g)", 120, 2.0, 13.0, 6.7, 1.0, "poori"),
        f("Breads & breakfast", "Naan, plain", "1 (90 g)", 260, 8.0, 45.0, 5.2, 2.0, "nan"),
        f("Breads & breakfast", "Butter / garlic naan", "1 (100 g)", 320, 8.5, 46.0, 11.0, 2.0, "garlic nan butter nan"),
        f("Breads & breakfast", "Tandoori roti", "1 (60 g)", 150, 5.0, 30.0, 1.2, 4.0, "tandoor roti"),
        f("Breads & breakfast", "Idli", "1 (40 g)", 60, 2.0, 12.0, 0.4, 0.5, "iddly idly"),
        f("Breads & breakfast", "Plain dosa", "1 medium", 170, 4.0, 27.0, 5.0, 1.0, "dosai"),
        f("Breads & breakfast", "Masala dosa", "1", 380, 7.0, 52.0, 16.0, 4.0, "dosai potato"),
        f("Breads & breakfast", "Rava dosa", "1", 250, 5.0, 34.0, 10.5, 1.0, "sooji dosa"),
        f("Breads & breakfast", "Uttapam", "1", 230, 6.0, 34.0, 7.5, 2.0, "uthappam"),
        f("Breads & breakfast", "Pesarattu", "1", 180, 9.0, 24.0, 5.3, 4.0, "moong dosa green gram"),
        f("Breads & breakfast", "Medu vada", "1 (50 g)", 140, 4.5, 13.0, 8.0, 2.0, "vada wada garelu"),
        f("Breads & breakfast", "Upma", "1 cup (200 g)", 250, 6.0, 36.0, 9.0, 3.0, "uppuma rava"),
        f("Breads & breakfast", "Poha", "1 cup (180 g)", 270, 5.0, 44.0, 8.2, 2.0, "aval atukulu"),
        f("Breads & breakfast", "Pongal (ven)", "1 cup (200 g)", 300, 8.0, 42.0, 11.0, 2.0, "khara pongal"),
        f("Breads & breakfast", "Onion bonda / pakoda", "1 piece (35 g)", 110, 2.0, 11.0, 6.4, 1.0, "bajji bhaji pakora bonda"),
        f("Breads & breakfast", "Samosa", "1 (90 g)", 260, 4.0, 28.0, 14.7, 2.0, ""),
        f("Breads & breakfast", "Bread, white", "1 slice (25 g)", 67, 2.2, 12.7, 0.9, 0.6, ""),
        f("Breads & breakfast", "Bread, brown / wholewheat", "1 slice (28 g)", 70, 3.5, 12.0, 1.0, 2.0, ""),
        f("Breads & breakfast", "Oats, cooked with water", "1 cup (240 g)", 150, 5.5, 27.0, 3.0, 4.0, "oatmeal porridge"),
        f("Breads & breakfast", "Cornflakes with milk", "1 bowl", 220, 7.0, 38.0, 4.5, 1.0, "cereal"),

        // Rice dishes
        f("Rice", "Rice, cooked (white)", "1 cup (160 g)", 205, 4.2, 45.0, 0.4, 0.6, "chawal annam sadam"),
        f("Rice", "Brown rice, cooked", "1 cup (195 g)", 215, 5.0, 45.0, 1.8, 3.5, ""),
        f("Rice", "Chicken biryani", "1 plate (350 g)", 650, 28.0, 75.0, 26.0, 3.0, "biriyani"),
        f("Rice", "Mutton biryani", "1 plate (350 g)", 720, 30.0, 75.0, 32.0, 3.0, "biriyani lamb goat"),
        f("Rice", "Veg biryani", "1 plate (300 g)", 480, 10.0, 72.0, 16.5, 5.0, "biriyani vegetable"),
        f("Rice", "Egg biryani", "1 plate (330 g)", 580, 20.0, 75.0, 22.0, 3.0, "biriyani anda"),
        f("Rice", "Pulao / jeera rice", "1 cup (180 g)", 280, 5.0, 46.0, 8.3, 1.5, "pulav"),
        f("Rice", "Curd rice", "1 cup (200 g)", 230, 6.0, 36.0, 6.8, 0.5, "thayir sadam perugu annam dahi chawal"),
        f("Rice", "Lemon rice", "1 cup (180 g)", 290, 5.0, 47.0, 9.0, 1.5, "pulihora chitranna"),
        f("Rice", "Sambar rice", "1 cup (250 g)", 280, 8.0, 47.0, 6.7, 4.0, "sambar sadam"),
        f("Rice", "Rice khichdi", "1 cup (250 g)", 310, 10.0, 48.0, 8.7, 4.0, "khichri kichdi"),
        f("Rice", "Fried rice (veg)", "1 plate (300 g)", 450, 9.0, 66.0, 16.5, 3.0, "chinese"),
        f("Rice", "Chicken fried rice", "1 plate (300 g)", 520, 20.0, 64.0, 20.0, 2.0, "chinese"),

        // Dal, curries, veg
        f("Dal & curries", "Dal (toor/moong), cooked", "1 katori (150 g)", 150, 8.0, 22.0, 3.2, 5.0, "daal pappu paruppu"),
        f("Dal & curries", "Dal tadka / dal fry", "1 katori (150 g)", 190, 8.0, 22.0, 7.6, 5.0, "daal"),
        f("Dal & curries", "Dal makhani", "1 katori (150 g)", 280, 9.0, 25.0, 15.6, 6.0, "daal"),
        f("Dal & curries", "Rajma curry", "1 katori (150 g)", 200, 9.0, 26.0, 6.7, 7.0, "kidney beans"),
        f("Dal & curries", "Chole / chana masala", "1 katori (150 g)", 230, 10.0, 30.0, 8.0, 8.0, "chickpea chana"),
        f("Dal & curries", "Sambar", "1 katori (150 g)", 110, 5.0, 15.0, 3.3, 4.0, ""),
        f("Dal & curries", "Rasam", "1 katori (150 g)", 50, 1.5, 7.0, 1.8, 1.0, "charu"),
        f("Dal & curries", "Mixed veg curry", "1 katori (150 g)", 150, 4.0, 14.0, 8.9, 4.0, "sabzi sabji kura"),
        f("Dal & curries", "Aloo sabzi", "1 katori (150 g)", 180, 3.0, 22.0, 9.0, 3.0, "potato curry"),
        f("Dal & curries", "Bhindi fry", "1 katori (120 g)", 150, 3.0, 11.0, 10.7, 4.0, "okra lady finger bendakaya"),
        f("Dal & curries", "Palak paneer", "1 katori (150 g)", 240, 11.0, 9.0, 17.8, 3.0, "spinach"),
        f("Dal & curries", "Paneer butter masala", "1 katori (150 g)", 350, 13.0, 13.0, 27.0, 2.0, "paneer makhani"),
        f("Dal & curries", "Kadai paneer", "1 katori (150 g)", 300, 13.0, 11.0, 22.7, 2.0, ""),
        f("Dal & curries", "Curd / dahi", "1 katori (150 g)", 90, 5.0, 6.0, 5.0, 0.0, "yogurt perugu thayir"),
        f("Dal & curries", "Raita", "1 katori (150 g)", 80, 4.0, 6.5, 4.3, 0.5, ""),
        f("Dal & curries", "Green salad", "1 bowl (150 g)", 30, 1.5, 6.0, 0.2, 2.0, "cucumber onion tomato"),

        // Chicken, egg, fish, meat
        f("Protein", "Chicken curry", "1 katori (150 g)", 250, 20.0, 6.0, 16.4, 1.0, "kodi kura murgh"),
        f("Protein", "Butter chicken", "1 katori (150 g)", 330, 20.0, 9.0, 24.0, 1.0, "murgh makhani"),
        f("Protein", "Tandoori chicken", "1 leg piece (150 g)", 260, 32.0, 4.0, 12.7, 0.5, "tandoor"),
        f("Protein", "Chicken tikka", "6 pieces (150 g)", 230, 35.0, 4.0, 8.0, 0.5, "tikka kebab"),
        f("Protein", "Chicken 65 / fried starter", "100 g", 290, 20.0, 12.0, 18.0, 0.5, "chicken fry lollipop"),
        f("Protein", "Grilled chicken breast", "100 g", 165, 31.0, 0.0, 3.6, 0.0, ""),
        f("Protein", "Chicken breast, boiled", "100 g", 150, 30.0, 0.0, 3.0, 0.0, ""),
        f("Protein", "Mutton curry", "1 katori (150 g)", 330, 22.0, 6.0, 24.0, 1.0, "goat lamb"),
        f("Protein", "Fish curry", "1 katori (150 g)", 200, 20.0, 5.0, 11.0, 1.0, "chepala pulusu meen"),
        f("Protein", "Fish fry", "1 piece (100 g)", 230, 20.0, 6.0, 14.0, 0.5, ""),
        f("Protein", "Prawn curry", "1 katori (150 g)", 200, 22.0, 5.0, 10.0, 1.0, "shrimp royyala"),
        f("Protein", "Boiled egg", "1 large", 75, 6.3, 0.6, 5.3, 0.0, "anda egg"),
        f("Protein", "Egg white, boiled", "1", 17, 3.6, 0.2, 0.1, 0.0, ""),
        f("Protein", "Omelette (2 eggs)", "1", 200, 13.0, 2.0, 15.5, 0.0, "omlet"),
        f("Protein", "Egg bhurji (2 eggs)", "1 serving", 220, 13.0, 4.0, 16.5, 0.5, "scrambled eggs"),
        f("Protein", "Egg curry (2 eggs)", "1 katori", 260, 14.0, 8.0, 19.0, 1.0, "anda curry"),
        f("Protein", "Paneer, raw", "100 g", 265, 18.0, 3.6, 20.8, 0.0, "cottage cheese"),
        f("Protein", "Paneer tikka", "6 pieces (150 g)", 320, 21.0, 7.0, 23.0, 1.0, ""),
        f("Protein", "Soya chunks, cooked", "1 cup (50 g dry)", 170, 26.0, 16.0, 0.4, 6.0, "meal maker nutrela"),
        f("Protein", "Tofu", "100 g", 80, 8.0, 2.0, 4.5, 0.3, ""),
        f("Protein", "Sprouts (moong)", "1 cup (100 g)", 30, 3.0, 6.0, 0.2, 1.8, "mung sprouts"),
        f("Protein", "Roasted chana", "1 handful (30 g)", 110, 6.0, 17.0, 1.8, 5.0, "chana dal roasted putnalu"),
        f("Protein", "Whey protein scoop", "1 scoop (30 g)", 120, 24.0, 3.0, 1.5, 0.0, "protein shake"),

        // Drinks and dairy
        f("Drinks", "Chai with sugar", "1 cup (150 ml)", 90, 3.0, 13.0, 3.0, 0.0, "tea"),
        f("Drinks", "Chai without sugar", "1 cup (150 ml)", 45, 2.5, 3.5, 2.4, 0.0, "tea"),
        f("Drinks", "Coffee with milk & sugar", "1 cup", 90, 3.0, 13.0, 3.0, 0.0, "filter coffee"),
        f("Drinks", "Black coffee", "1 cup", 5, 0.3, 0.5, 0.0, 0.0, ""),
        f("Drinks", "Milk, toned", "1 glass (250 ml)", 145, 8.0, 12.0, 7.5, 0.0, "doodh"),
        f("Drinks", "Buttermilk", "1 glass (250 ml)", 40, 2.5, 4.5, 1.3, 0.0, "chaas majjiga mor"),
        f("Drinks", "Lassi, sweet", "1 glass (250 ml)", 220, 7.0, 34.0, 6.3, 0.0, ""),
        f("Drinks", "Amul high-protein buttermilk", "1 pack (200 ml)", 70, 15.0, 2.0, 0.5, 0.0, "protein buttermilk"),
        f("Drinks", "Coconut water", "1 glass (250 ml)", 45, 0.5, 10.0, 0.2, 1.0, "kobbari neellu"),
        f("Drinks", "Soft drink / cola", "1 can (330 ml)", 140, 0.0, 35.0, 0.0, 0.0, "coke pepsi soda"),
        f("Drinks", "Fruit juice", "1 glass (250 ml)", 120, 1.0, 28.0, 0.3, 0.5, "juice"),
        f("Drinks", "Beer", "1 pint (500 ml)", 215, 2.0, 18.0, 0.0, 0.0, ""),

        // Fruit
        f("Fruit", "Banana", "1 medium", 105, 1.3, 27.0, 0.4, 3.1, "kela arati"),
        f("Fruit", "Apple", "1 medium", 95, 0.5, 25.0, 0.3, 4.4, "seb"),
        f("Fruit", "Orange", "1 medium", 60, 1.2, 15.0, 0.2, 3.0, ""),
        f("Fruit", "Papaya", "1 cup (145 g)", 60, 0.7, 15.0, 0.4, 2.5, ""),
        f("Fruit", "Watermelon", "1 cup (150 g)", 45, 0.9, 11.0, 0.2, 0.6, ""),
        f("Fruit", "Mango", "1 cup (165 g)", 100, 1.4, 25.0, 0.6, 2.6, "aam mamidi"),
        f("Fruit", "Guava", "1 medium", 40, 1.4, 8.0, 0.5, 3.0, "jama amrood"),
        f("Fruit", "Grapes", "1 cup (150 g)", 100, 1.0, 27.0, 0.2, 1.4, ""),

        // Snacks and sweets
        f("Snacks & sweets", "Peanuts, roasted", "1 handful (30 g)", 170, 7.5, 6.0, 14.5, 2.5, "groundnuts palli"),
        f("Snacks & sweets", "Almonds", "10 pieces", 70, 2.5, 2.5, 6.0, 1.5, "badam"),
        f("Snacks & sweets", "Mixture / namkeen", "1 handful (30 g)", 160, 3.0, 15.0, 10.0, 1.5, "bhujia"),
        f("Snacks & sweets", "Chips", "1 small pack (30 g)", 160, 2.0, 15.0, 10.0, 1.0, "lays"),
        f("Snacks & sweets", "Biscuits (Marie)", "4 biscuits", 110, 2.0, 19.0, 3.0, 0.5, ""),
        f("Snacks & sweets", "Gulab jamun", "1 piece", 150, 2.0, 22.0, 6.0, 0.0, "sweet"),
        f("Snacks & sweets", "Rasgulla", "1 piece", 120, 2.0, 26.0, 1.0, 0.0, "sweet"),
        f("Snacks & sweets", "Jalebi", "1 piece (30 g)", 150, 1.0, 25.0, 5.3, 0.0, "sweet"),
        f("Snacks & sweets", "Laddu", "1 piece (40 g)", 180, 3.0, 22.0, 9.0, 1.0, "ladoo sweet"),
        f("Snacks & sweets", "Ice cream", "1 scoop (65 g)", 140, 2.5, 16.0, 7.3, 0.0, ""),
        f("Snacks & sweets", "Dark chocolate", "2 squares (20 g)", 110, 1.5, 9.0, 8.0, 2.0, ""),
        f("Snacks & sweets", "Pani puri", "6 pieces", 180, 3.0, 28.0, 6.0, 2.0, "golgappa gupchup"),
        f("Snacks & sweets", "Pav bhaji", "1 plate (2 pav)", 450, 10.0, 62.0, 18.0, 6.0, ""),
        f("Snacks & sweets", "Vada pav", "1", 290, 6.0, 40.0, 12.0, 3.0, ""),

        // Fast food
        f("Fast food", "Veg burger", "1", 350, 9.0, 45.0, 14.5, 3.0, ""),
        f("Fast food", "Chicken burger", "1", 420, 20.0, 40.0, 20.0, 2.0, ""),
        f("Fast food", "Pizza slice (cheese)", "1 slice (medium)", 250, 10.0, 30.0, 10.0, 2.0, ""),
        f("Fast food", "French fries", "1 medium", 330, 4.0, 43.0, 16.0, 4.0, "fries"),
        f("Fast food", "Chicken shawarma roll", "1", 450, 25.0, 40.0, 21.0, 3.0, "shawarma wrap"),
        f("Fast food", "Veg noodles / hakka", "1 plate (300 g)", 420, 9.0, 62.0, 15.0, 3.0, "chowmein"),
        f("Fast food", "Chicken noodles", "1 plate (300 g)", 480, 20.0, 58.0, 18.5, 2.0, "chowmein"),
        f("Fast food", "Momos (chicken, steamed)", "6 pieces", 250, 14.0, 30.0, 8.0, 1.0, "dumplings"),
        f("Fast food", "Momos (veg, steamed)", "6 pieces", 200, 6.0, 32.0, 5.3, 2.0, "dumplings"),
    )

    /** Search by name, other names and group. Best matches first. */
    private val MULT = Regex(""" ×(\d*½|\d+(?:\.\d+)?)$""")

    /**
     * Turns a logged entry back into one portion, so it can be logged again: "Rice, cooked (white) ×3" at 615 kcal
     * becomes "Rice, cooked (white)" at 205 kcal, with the list's portion text when it's a known food.
     */
    fun fromLogged(name: String, kcal: Double, protein: Double?, carbs: Double?, fat: Double?, fiber: Double?): FoodItem {
        val m = MULT.find(name)
        val n = when (val g = m?.groupValues?.get(1)) {
            null -> 1.0
            else -> if (g.endsWith("½")) (g.dropLast(1).toDoubleOrNull() ?: 0.0) + 0.5 else g.toDoubleOrNull() ?: 1.0
        }.coerceAtLeast(0.25)
        val base = if (m != null) name.removeRange(m.range).trim() else name.trim()
        val known = ALL.firstOrNull { it.name.equals(base, ignoreCase = true) }
        return FoodItem(
            base, known?.portion ?: "1 portion", kcal / n, (protein ?: 0.0) / n, (carbs ?: 0.0) / n, (fat ?: 0.0) / n, (fiber ?: 0.0) / n,
            group = known?.group ?: "Recent",
        )
    }

    fun search(query: String): List<FoodItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val words = q.split(" ").filter { it.isNotBlank() }
        return ALL.mapNotNull { f ->
            val name = f.name.lowercase()
            val hay = "$name ${f.aka.lowercase()} ${f.group.lowercase()}"
            if (!words.all { hay.contains(it) }) return@mapNotNull null
            val score = when {
                name.startsWith(q) -> 0
                name.contains(q) -> 1
                else -> 2
            }
            score to f
        }.sortedWith(compareBy({ it.first }, { it.second.name.length })).map { it.second }.take(30)
    }
}
