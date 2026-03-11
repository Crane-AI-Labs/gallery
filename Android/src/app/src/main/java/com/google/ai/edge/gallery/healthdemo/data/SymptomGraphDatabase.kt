package com.google.ai.edge.gallery.healthdemo.data

/**
 * Local symptom-to-guidance graph database.
 *
 * Each node in the graph maps a set of symptom keywords to a possible condition,
 * suggested treatment steps, and recommended next steps.  The lookup walks the
 * graph and returns the best-matching node.
 */
object SymptomGraphDatabase {

    data class GuidanceNode(
        val keywords: List<String>,
        val possibleCondition: String,
        val suggestedTreatment: List<String>,
        val recommendedNextSteps: List<String>
    )

    private val graph: List<GuidanceNode> = listOf(

        // --- Fever / Febrile illness ---
        GuidanceNode(
            keywords = listOf("fever", "high temperature", "febrile", "hot", "sweating", "chills", "malaria"),
            possibleCondition = "Possible febrile illness",
            suggestedTreatment = listOf(
                "Monitor temperature regularly",
                "Ensure adequate hydration",
                "Consider antipyretic if temperature exceeds 38.5°C",
                "Rest and observe for other symptoms"
            ),
            recommendedNextSteps = listOf(
                "Monitor patient for 24–48 hours",
                "Refer to higher facility if symptoms worsen",
                "Watch for danger signs (difficulty breathing, confusion, persistent vomiting)"
            )
        ),

        // --- Malaria-specific (high priority keyword override) ---
        GuidanceNode(
            keywords = listOf("malaria", "mosquito", "rigors", "jaundice", "yellow eyes"),
            possibleCondition = "Suspected malaria",
            suggestedTreatment = listOf(
                "Perform RDT (Rapid Diagnostic Test) if available",
                "Administer artemisinin-based combination therapy (ACT) per national protocol if RDT positive",
                "Ensure adequate hydration and rest",
                "Monitor temperature every 4 hours"
            ),
            recommendedNextSteps = listOf(
                "Refer immediately if severe malaria signs present (impaired consciousness, difficulty breathing)",
                "Follow up in 48 hours to confirm treatment response",
                "Educate on insecticide-treated bed net use"
            )
        ),

        // --- Respiratory / Cough ---
        GuidanceNode(
            keywords = listOf("cough", "coughing", "breathing", "breathlessness", "chest", "wheeze", "wheezing", "pneumonia", "respiratory"),
            possibleCondition = "Possible respiratory tract infection",
            suggestedTreatment = listOf(
                "Count respiratory rate for 1 full minute",
                "Check for chest indrawing or nasal flaring",
                "Measure oxygen saturation if pulse oximeter is available",
                "Ensure adequate hydration and upright positioning"
            ),
            recommendedNextSteps = listOf(
                "Monitor breathing closely for signs of distress",
                "Refer urgently if chest indrawing, SpO2 < 90%, or inability to drink",
                "Provide age-appropriate antibiotic if bacterial pneumonia is suspected"
            )
        ),

        // --- Diarrhoea / Dehydration ---
        GuidanceNode(
            keywords = listOf("diarrhea", "diarrhoea", "loose stool", "watery stool", "vomiting", "stomach", "nausea", "gastro"),
            possibleCondition = "Possible gastroenteritis / dehydration risk",
            suggestedTreatment = listOf(
                "Assess dehydration level (sunken eyes, skin turgor, thirst)",
                "Start oral rehydration salts (ORS) immediately",
                "Continue breastfeeding for infants",
                "Check for blood in stool (dysentery)"
            ),
            recommendedNextSteps = listOf(
                "Monitor hydration status every 2 hours",
                "Refer immediately if severe dehydration, unconsciousness, or blood in stool",
                "Educate caregiver on continued ORS administration"
            )
        ),

        // --- Rash / Skin ---
        GuidanceNode(
            keywords = listOf("rash", "skin", "itching", "itch", "blisters", "redness", "hives", "spots", "lesion", "chickenpox", "measles"),
            possibleCondition = "Possible skin condition or infectious rash",
            suggestedTreatment = listOf(
                "Document rash location, size, and character (blisters, flat, raised)",
                "Check for associated fever, sore throat, or joint pain",
                "Keep affected area clean and dry",
                "Avoid scratching to prevent secondary infection"
            ),
            recommendedNextSteps = listOf(
                "Monitor rash for spreading or change in character",
                "Refer if rash is spreading rapidly, accompanied by high fever, or affects mucous membranes",
                "Isolate if measles or chickenpox is suspected"
            )
        ),

        // --- Headache / Neurological ---
        GuidanceNode(
            keywords = listOf("headache", "head pain", "migraine", "neck stiffness", "stiff neck", "confusion", "seizure", "convulsion", "unconscious"),
            possibleCondition = "Possible neurological or meningeal involvement",
            suggestedTreatment = listOf(
                "Assess level of consciousness using AVPU scale",
                "Check for neck stiffness, sensitivity to light, and rash",
                "Measure temperature and blood pressure",
                "Do not give aspirin to children under 16"
            ),
            recommendedNextSteps = listOf(
                "Refer immediately if neck stiffness, altered consciousness, or seizures present",
                "Keep airway clear and patient in recovery position if unconscious",
                "Do not delay referral for severe headache with fever"
            )
        ),

        // --- Anaemia / Pallor ---
        GuidanceNode(
            keywords = listOf("anaemia", "anemia", "pale", "pallor", "weakness", "fatigue", "tired", "dizzy", "dizziness"),
            possibleCondition = "Possible anaemia or general weakness",
            suggestedTreatment = listOf(
                "Check palmar pallor, conjunctival pallor, and nail bed colour",
                "Measure haemoglobin if point-of-care testing available",
                "Assess diet and recent illness history",
                "Check for signs of acute blood loss"
            ),
            recommendedNextSteps = listOf(
                "Refer if severe palmar pallor or signs of decompensation",
                "Start iron supplementation per national protocol if mild anaemia",
                "Treat underlying cause (malaria, worms, nutritional deficiency)"
            )
        ),

        // --- Malnutrition ---
        GuidanceNode(
            keywords = listOf("malnutrition", "underweight", "wasting", "stunting", "kwashiorkor", "marasmus", "not eating", "poor appetite", "weight loss"),
            possibleCondition = "Possible malnutrition",
            suggestedTreatment = listOf(
                "Measure MUAC (Mid-Upper Arm Circumference) and weight-for-height",
                "Check for bilateral pitting oedema",
                "Assess feeding practices and diet history",
                "Initiate ready-to-use therapeutic food (RUTF) if SAM diagnosed"
            ),
            recommendedNextSteps = listOf(
                "Refer to therapeutic feeding programme if SAM criteria met",
                "Counsel caregiver on appropriate feeding practices",
                "Follow up weekly to monitor weight gain"
            )
        ),

        // --- Maternal / Obstetric ---
        GuidanceNode(
            keywords = listOf("pregnant", "pregnancy", "labour", "labor", "bleeding", "vaginal", "contractions", "prenatal", "antenatal", "postpartum", "delivery"),
            possibleCondition = "Obstetric concern requiring assessment",
            suggestedTreatment = listOf(
                "Confirm gestational age and expected due date",
                "Check blood pressure, pulse, and fetal heart rate",
                "Assess for danger signs: heavy bleeding, severe headache, visual disturbance",
                "Do not delay if woman is in active labour"
            ),
            recommendedNextSteps = listOf(
                "Refer immediately to a skilled birth attendant or health facility",
                "Do not attempt home delivery without trained personnel present",
                "Ensure woman is accompanied during transfer"
            )
        ),

        // --- Eye / Ear ---
        GuidanceNode(
            keywords = listOf("eye", "eyes", "vision", "ear", "ears", "hearing", "discharge", "pain in ear", "red eye", "conjunctivitis"),
            possibleCondition = "Possible eye or ear infection",
            suggestedTreatment = listOf(
                "Inspect for redness, discharge, or swelling",
                "Assess visual acuity if eye is affected",
                "Check ear canal for discharge or tenderness on pressure",
                "Clean gently with sterile cotton; do not insert objects"
            ),
            recommendedNextSteps = listOf(
                "Apply antibiotic eye drops or ear drops per protocol if infection confirmed",
                "Refer to clinician if vision is affected or symptoms persist > 3 days",
                "Follow up in 48 hours"
            )
        ),

        // --- Wound / Injury ---
        GuidanceNode(
            keywords = listOf("wound", "cut", "injury", "bleeding", "bruise", "fracture", "burn", "trauma", "bite", "snake bite"),
            possibleCondition = "Traumatic injury requiring care",
            suggestedTreatment = listOf(
                "Control bleeding with direct pressure",
                "Clean wound thoroughly with clean water and soap",
                "Cover with sterile dressing",
                "Assess tetanus immunisation status"
            ),
            recommendedNextSteps = listOf(
                "Refer immediately for deep wounds, fractures, burns >1% body surface, or animal bites",
                "Administer tetanus toxoid if not up to date",
                "Monitor wound for signs of infection (redness, pus, warmth)"
            )
        ),

        // --- Urinary ---
        GuidanceNode(
            keywords = listOf("urine", "urinary", "dysuria", "burning urination", "frequency", "uti", "bladder", "kidney", "flank pain"),
            possibleCondition = "Possible urinary tract infection",
            suggestedTreatment = listOf(
                "Ask about painful urination, frequency, and colour of urine",
                "Check for fever and flank pain (possible pyelonephritis)",
                "Encourage increased fluid intake",
                "Perform urine dipstick if available"
            ),
            recommendedNextSteps = listOf(
                "Start antibiotic therapy per national protocol if UTI confirmed",
                "Refer if fever > 38°C with flank pain (suggests upper UTI)",
                "Follow up in 3 days to confirm resolution"
            )
        ),

        // --- Generic fallback ---
        GuidanceNode(
            keywords = emptyList(),
            possibleCondition = "General assessment required",
            suggestedTreatment = listOf(
                "Perform a full head-to-toe clinical assessment",
                "Measure all vital signs: temperature, pulse, blood pressure, respiratory rate",
                "Take a detailed history: onset, duration, any previous treatments",
                "Check for danger signs: lethargy, inability to eat or drink, convulsions"
            ),
            recommendedNextSteps = listOf(
                "Monitor patient closely and reassess in 1–2 hours",
                "Apply appropriate treatment based on your clinical findings",
                "Refer to senior clinician or higher-level facility if diagnosis is unclear"
            )
        )
    )

    /**
     * Query the graph for the best-matching guidance node.
     *
     * Scoring: count how many keywords from a node appear in the symptom text.
     * The node with the highest score wins; the generic fallback is used when
     * no keywords match.
     */
    fun query(symptoms: String, age: AgeRange? = null, sex: Sex? = null): GuidanceNode {
        val lower = symptoms.lowercase()

        var bestNode = graph.last() // fallback
        var bestScore = 0

        for (node in graph.dropLast(1)) { // exclude fallback from scoring
            val score = node.keywords.count { kw -> lower.contains(kw) }
            if (score > bestScore) {
                bestScore = score
                bestNode = node
            }
        }

        // If nothing matched, use the generic fallback
        return bestNode
    }
}
