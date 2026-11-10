package com.photoselectortoolbox.domain.learn

import com.photoselectortoolbox.data.cache.TechniqueFactEntity

enum class LessonTrack(val title: String) {
    LIGHT("Light"),
    COMPOSITION("Composition"),
    MOTION_AND_DEPTH("Motion & Depth"),
    EXPOSURE_AND_COLOR("Exposure & Color"),
}

data class MicroLesson(
    val id: String,
    val track: LessonTrack,
    val title: String,
    val conceptSummary: String,
    val diagramType: String,
    val factKind: String,
    val positiveFactValues: Set<String>,
    val negativeFactValues: Set<String>,
    val tryThisPrompt: String,
    val fallbackQuestionPrompt: String,
    val fallbackOptions: List<String>,
    val fallbackCorrectIndex: Int,
)

data class SpotItQuestion(
    val prompt: String,
    val options: List<String>,
    val correctIndex: Int,
    val examplePhotoUri: String? = null,
    val contrastPhotoUri: String? = null,
    val explanation: String,
)

/**
 * Built-in catalog of 14 micro-lessons across 4 tracks (`U-26`).
 * Matches lessons against the user's own scanned photos via `technique_facts`
 * and builds a 2-question "Spot it" quiz per lesson.
 */
object LessonCatalog {

    val ALL_LESSONS: List<MicroLesson> = listOf(
        // Track 1 — Light (3 lessons)
        MicroLesson(
            id = "light.golden_hour",
            track = LessonTrack.LIGHT,
            title = "Golden & Blue Hour",
            conceptSummary = "When the sun sits between 0° and +6° above the horizon (Golden Hour), its rays pass through more atmosphere, scattering harsh blue wavelengths and bathing scenes in warm, directional light with long, gentle shadows. Just before sunrise or after sunset (-6° to -0.8° solar elevation, Blue Hour), the indirect sky glow turns a deep cobalt that balances naturally with warm city or interior lights.",
            diagramType = "solar_elevation_arc",
            factKind = "light.hour",
            positiveFactValues = setOf("golden_hour", "blue_hour"),
            negativeFactValues = setOf("day", "night"),
            tryThisPrompt = "Head out 30 minutes before sunset and photograph the same subject with front, side, and rim lighting.",
            fallbackQuestionPrompt = "At what solar elevation angle does Golden Hour occur?",
            fallbackOptions = listOf("Between -0.8° and +6° near the horizon", "Above +45° at solar noon", "Below -18° astronomical night"),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "light.direction",
            track = LessonTrack.LIGHT,
            title = "Side Light vs Backlight & Silhouettes",
            conceptSummary = "Front light illuminates evenly but flattens texture. Side light rakes across surfaces, revealing three-dimensional form and micro-contrast. Backlight places the light source behind your subject: expose for the subject to get a glowing rim halo, or expose for the bright sky to turn the subject into a bold graphic silhouette.",
            diagramType = "light_direction_angles",
            factKind = "exposure.tonal_key",
            positiveFactValues = setOf("silhouette"),
            negativeFactValues = setOf("normal"),
            tryThisPrompt = "Position a subject against a bright sky or window and meter on the background to create a clean silhouette outline.",
            fallbackQuestionPrompt = "What creates a crisp silhouette when shooting into backlight?",
            fallbackOptions = listOf("Exposing for the bright background so the subject falls into deep shadow", "Using front-on flash at full power", "Overexposing the entire frame by +3 EV"),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "light.tonal_key",
            track = LessonTrack.LIGHT,
            title = "High-Key & Low-Key Exposure",
            conceptSummary = "Camera meters average every scene toward 18% middle gray. A High-Key photograph intentionally shifts the histogram toward bright whites and airy pastels with minimal shadow weight, while a Low-Key photograph anchors most of the frame in rich dark tones with selective highlight accents to evoke mystery and drama.",
            diagramType = "tonal_key_histograms",
            factKind = "exposure.tonal_key",
            positiveFactValues = setOf("high_key", "low_key"),
            negativeFactValues = setOf("normal"),
            tryNextTimeFallback = "Use +1 EV on bright minimalist scenes for high-key, or -1 to -2 EV on dark backgrounds for low-key.",
        ),

        // Track 2 — Composition (5 lessons)
        MicroLesson(
            id = "composition.rule_of_thirds",
            track = LessonTrack.COMPOSITION,
            title = "Rule of Thirds & Subject Placement",
            conceptSummary = "Dividing the frame into a 3×3 grid creates four intersecting power points. Placing your primary subject near one of these intersections—rather than dead center by default—introduces dynamic visual balance and leaves open space ('lead room') for the subject to look or move into.",
            diagramType = "thirds_grid_power_points",
            factKind = "composition.subject_placement",
            positiveFactValues = setOf("thirds_aligned"),
            negativeFactValues = setOf("edge_crowded", "off_center"),
            tryThisPrompt = "Place your subject's eye or focal point on one of the four 3×3 grid intersections and leave two-thirds open in their gaze direction.",
            fallbackQuestionPrompt = "Where should the open two-thirds of the frame usually be placed when framing a portrait or moving subject?",
            fallbackOptions = listOf("In the direction the subject is looking or moving", "Behind the subject's back", "Clipped tightly against the nearest frame edge"),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "composition.leading_lines",
            track = LessonTrack.COMPOSITION,
            title = "Leading Lines",
            conceptSummary = "Roads, shorelines, railings, shadows, and architectural edges act as visual pathways. When positioned so they enter from the foreground and converge toward your subject, leading lines create a strong sense of three-dimensional depth and pull the viewer's eye directly to the center of interest.",
            diagramType = "converging_perspective_lines",
            factKind = "composition.leading_lines",
            positiveFactValues = setOf("converging_to_subject"),
            negativeFactValues = emptySet(),
            tryThisPrompt = "Find a path, fence, or architectural line and crouch low so the line starts near a bottom corner and leads to your subject.",
            fallbackQuestionPrompt = "When is a leading line most effective in composition?",
            fallbackOptions = listOf("When it guides the viewer's eye toward the primary subject", "When it exits the frame without touching any subject", "When it cuts across the neck of a portrait subject"),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "composition.horizon",
            track = LessonTrack.COMPOSITION,
            title = "Level Horizons & Where to Place Them",
            conceptSummary = "The human visual system is acutely sensitive to liquid and distant horizons: even a 1° tilt makes a seascape feel like it is pouring out of the frame. Once level, avoid splitting the frame 50/50 unless reflecting symmetry—place the horizon on the lower third to showcase dramatic skies, or the upper third to emphasize foreground texture.",
            diagramType = "horizon_level_and_thirds",
            factKind = "composition.horizon",
            positiveFactValues = setOf("level"),
            negativeFactValues = setOf("slight_tilt", "tilted"),
            tryThisPrompt = "Turn on your viewfinder level indicator and shoot two frames: one with the horizon on the lower third, one on the upper third.",
            fallbackQuestionPrompt = "Why is a 1.5° horizon tilt on a lake or ocean scene problematic?",
            fallbackOptions = listOf("Our eyes immediately notice tilted water lines, making the frame feel off-balance", "It increases sensor ISO noise", "It causes optical lens diffraction"),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "composition.simplicity",
            track = LessonTrack.COMPOSITION,
            title = "Simplicity & Background Control",
            conceptSummary = "Photography is an art of subtraction. High-contrast clutter, bright signs, or branches around the periphery of the frame compete with your subject for attention. Changing your vantage point by a single step, zooming in, or opening the aperture removes distractions and gives the subject a clean stage.",
            diagramType = "figure_ground_simplicity",
            factKind = "composition.simplicity",
            positiveFactValues = setOf("clean"),
            negativeFactValues = setOf("busy"),
            tryThisPrompt = "Before pressing the shutter, run your eyes around all four borders of the frame to spot and eliminate background distractions.",
            fallbackQuestionPrompt = "What is the fastest way to simplify a busy background without post-processing?",
            fallbackOptions = listOf("Change your camera angle or move closer so distractions leave the frame", "Stop down to f/22 so every background twig is razor sharp", "Underexpose by 3 stops"),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "composition.symmetry",
            track = LessonTrack.COMPOSITION,
            title = "Symmetry & Centering on Purpose",
            conceptSummary = "While the Rule of Thirds creates dynamic tension, bilateral symmetry creates calm, formal power. Reflections on still water, vaulted corridors, and centered facades work best when the symmetry axis sits dead-center—any slight offset breaks the geometric spell.",
            diagramType = "bilateral_symmetry_axis",
            factKind = "composition.symmetry",
            positiveFactValues = setOf("vertical", "horizontal"),
            negativeFactValues = emptySet(),
            tryThisPrompt = "Find a reflection or architectural corridor and align the central axis precisely at the 50% mark of your viewfinder.",
            fallbackQuestionPrompt = "When centering a subject or horizon, what makes it look intentional rather than accidental?",
            fallbackOptions = listOf("Strong bilateral symmetry across the vertical or horizontal axis", "A 3° horizon tilt", "Clipping the top of the subject's head"),
            fallbackCorrectIndex = 0,
        ),

        // Track 3 — Motion & Depth (4 lessons)
        MicroLesson(
            id = "motion.reciprocal_rule",
            track = LessonTrack.MOTION_AND_DEPTH,
            title = "Hand-Holding Guideline (1 / Effective Focal Length)",
            conceptSummary = "Telephoto lenses and cropped sensors magnify angular hand movement. The classical hand-holding guideline states that your shutter denominator should be at least the 35mm-equivalent focal length: for a 50mm lens on a 1.5× APS-C body (75mm 35mm-eq), aim for at least 1/80s when shooting handheld without stabilization.",
            diagramType = "reciprocal_rule_cone",
            factKind = "optics.effective_focal_length",
            positiveFactValues = emptySet(),
            negativeFactValues = emptySet(),
            tryThisPrompt = "Check your lens's 35mm-equivalent focal length and test handheld sharpness at that speed versus 2 stops slower.",
            fallbackQuestionPrompt = "For a 100mm lens on a 1.5× APS-C sensor (150mm 35mm-eq), what is the baseline handheld shutter guideline?",
            fallbackOptions = listOf("Approximately 1/150s (or faster, e.g. 1/160s)", "1/30s", "1/15s"),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "motion.shutter_intent",
            track = LessonTrack.MOTION_AND_DEPTH,
            title = "Freezing Action vs Intentional Motion Blur",
            conceptSummary = "Shutter speed controls how time is rendered. Fast shutter speeds (1/1000s and faster) freeze athletes, birds, and splashing water in mid-air. Slow shutter speeds (1/15s for panning, 0.5s–30s on a tripod) turn rushing waterfalls into silk and city traffic into ribbons of light.",
            diagramType = "shutter_time_scale",
            factKind = "intent.shutter",
            positiveFactValues = setOf("action_freeze", "long_exposure"),
            negativeFactValues = emptySet(),
            tryThisPrompt = "Photograph moving water or a cyclist twice: once at 1/1000s to freeze every detail, and once at 1/15s or slower to show motion.",
            fallbackQuestionPrompt = "Which shutter speed range is best suited to freeze a fast-moving bird or sports action?",
            fallbackOptions = listOf("1/1000s or faster", "1/15s handheld", "2 seconds on a tripod"),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "depth.aperture_story",
            track = LessonTrack.MOTION_AND_DEPTH,
            title = "Aperture as a Storytelling Tool",
            conceptSummary = "Wide apertures (f/1.4–f/2.8 on full-frame/APS-C) create a shallow plane of focus, dissolving distracting backgrounds into creamy bokeh so the subject stands alone. Narrower apertures (f/8–f/11) expand depth of field from foreground rocks to distant mountain peaks when the environment is part of the story.",
            diagramType = "dof_aperture_cone",
            factKind = "depth.background_separation",
            positiveFactValues = setOf("strong_isolation", "moderate_separation"),
            negativeFactValues = setOf("deep_dof"),
            tryThisPrompt = "Frame a close subject with a distant background at your lens's widest f-stop, then repeat at f/8 to compare how the story changes.",
            fallbackQuestionPrompt = "How do you maximize optical background blur (shallow depth of field) on a dedicated camera?",
            fallbackOptions = listOf("Use a wide aperture (low f-number), get closer to the subject, and increase subject-to-background distance", "Stop down to f/16 and step far back from the subject", "Use a 14mm ultra-wide lens at f/11"),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "depth.diffraction",
            track = LessonTrack.MOTION_AND_DEPTH,
            title = "Lens Sweet Spot vs Diffraction",
            conceptSummary = "Stopping a lens down 2–3 stops from wide open (typically f/5.6–f/8 full-frame equivalent) sharpens corner aberrations. However, stopping down too far (f/16–f/22 equivalent) forces light waves through a tiny pinhole, causing optical diffraction that softens fine texture across the entire frame—and smaller sensors reach diffraction at wider physical f-numbers (N_eq = N × cropFactor).",
            diagramType = "diffraction_airy_disk",
            factKind = "optics.crop_factor",
            positiveFactValues = emptySet(),
            negativeFactValues = emptySet(),
            tryThisPrompt = "Compare fine foliage or brick texture shot at f/5.6 versus f/22 at 100% zoom to see diffraction softening firsthand.",
            fallbackQuestionPrompt = "Why does f/16 on a 1.5× APS-C sensor show as much diffraction softening as f/24 on full-frame?",
            fallbackOptions = listOf("Smaller sensors magnify the Airy disk by the crop factor (N_eq = N × C)", "APS-C lenses have no glass coatings", "Shutter speed changes the Airy disk size"),
            fallbackCorrectIndex = 0,
        ),

        // Track 4 — Exposure & Color (2 lessons)
        MicroLesson(
            id = "exposure.histogram_clipping",
            track = LessonTrack.EXPOSURE_AND_COLOR,
            title = "Reading the Histogram & Protecting Highlights",
            conceptSummary = "Your screen brightness can fool you outdoors, but the histogram never lies. When the graph piles up against the right wall, non-specular highlights (clouds, skin, wedding dresses) are clipping. In 8-bit JPEGs, clipped highlights are permanently white; in RAW files, 1–2 EV of headroom may be recoverable, while tiny specular glints on water or chrome are safe to ignore.",
            diagramType = "histogram_clipping_walls",
            factKind = "exposure.clipping",
            positiveFactValues = setOf("well_exposed"),
            negativeFactValues = setOf("highlights_clipped", "both_clipped", "shadows_clipped"),
            tryThisPrompt = "Enable highlight zebra warnings or live histogram and adjust EV so cloud textures stay just below the right edge.",
            fallbackQuestionPrompt = "Which type of highlight clipping is safe to ignore when judging exposure?",
            fallbackOptions = listOf("Tiny specular glints on chrome, water droplets, or the sun disc", "Broad blown patches across a subject's forehead", "An entire overcast sky clipped to 255"),
            fallbackCorrectIndex = 0,
        ),
        MicroLesson(
            id = "exposure.ev_metering",
            track = LessonTrack.EXPOSURE_AND_COLOR,
            title = "Exposure Compensation & Metering Modes",
            conceptSummary = "Matrix/evaluative metering balances most scenes well, but high-contrast spotlighting or snowy landscapes can trick the meter. Spot metering measures only a 1–3% circle on your subject, while Exposure Compensation (+/- EV) lets you bias Aperture-Priority or Shutter-Priority exposures without switching to full Manual.",
            diagramType = "metering_modes_ev_scale",
            factKind = "intent.exposure_bias",
            positiveFactValues = setOf("positive_ev", "negative_ev"),
            negativeFactValues = emptySet(),
            tryThisPrompt = "When shooting a subject in a bright spotlight against a dark stage, dial in -0.7 to -1.3 EV or switch to Spot metering.",
            fallbackQuestionPrompt = "When photographing a snow-covered landscape in Aperture Priority, why do you usually add +0.7 to +1.3 EV?",
            fallbackOptions = listOf("So the camera meter doesn't underexpose the bright snow into dull middle gray", "To intentionally blow out the sky", "To force a slower autofocus speed"),
            fallbackCorrectIndex = 0,
        ),
    )

    private fun MicroLesson(
        id: String,
        track: LessonTrack,
        title: String,
        conceptSummary: String,
        diagramType: String,
        factKind: String,
        positiveFactValues: Set<String>,
        negativeFactValues: Set<String>,
        tryNextTimeFallback: String,
    ) = MicroLesson(
        id = id,
        track = track,
        title = title,
        conceptSummary = conceptSummary,
        diagramType = diagramType,
        factKind = factKind,
        positiveFactValues = positiveFactValues,
        negativeFactValues = negativeFactValues,
        tryThisPrompt = tryNextTimeFallback,
        fallbackQuestionPrompt = "What characterizes a High-Key photograph's histogram?",
        fallbackOptions = listOf(
            "Most tones are concentrated in bright midtones and highlights without harsh shadow weight",
            "Every pixel is clipped to pure black (0)",
            "Only the green channel is present",
        ),
        fallbackCorrectIndex = 0,
    )

    fun getById(lessonId: String): MicroLesson? = ALL_LESSONS.find { it.id == lessonId }

    fun lessonsByTrack(): Map<LessonTrack, List<MicroLesson>> =
        ALL_LESSONS.groupBy { it.track }

    /**
     * Builds the 2-question "Spot it" quiz (`U-26`) for a lesson, drawing examples and contrasts
     * from the user's own scanned photos when available and falling back to built-in conceptual questions.
     */
    fun buildSpotItQuiz(
        lesson: MicroLesson,
        factsByUri: Map<String, List<TechniqueFactEntity>> = emptyMap(),
    ): List<SpotItQuestion> {
        val positiveMatches = mutableListOf<Pair<String, TechniqueFactEntity>>()
        val negativeMatches = mutableListOf<Pair<String, TechniqueFactEntity>>()

        for ((uri, facts) in factsByUri) {
            for (fact in facts) {
                if (fact.kind == lesson.factKind && fact.confidence >= 0.55) {
                    if (fact.value in lesson.positiveFactValues) {
                        positiveMatches.add(uri to fact)
                    } else if (fact.value in lesson.negativeFactValues) {
                        negativeMatches.add(uri to fact)
                    }
                }
            }
        }

        val q1 = SpotItQuestion(
            prompt = lesson.fallbackQuestionPrompt,
            options = lesson.fallbackOptions,
            correctIndex = lesson.fallbackCorrectIndex,
            examplePhotoUri = positiveMatches.firstOrNull()?.first,
            contrastPhotoUri = negativeMatches.firstOrNull()?.first,
            explanation = lesson.conceptSummary,
        )

        val q2 = if (positiveMatches.isNotEmpty() && negativeMatches.isNotEmpty()) {
            val pos = positiveMatches.first()
            val neg = negativeMatches.first()
            SpotItQuestion(
                prompt = "In your library, what distinguishes the strong example from the contrast frame for '${lesson.title}'?",
                options = listOf(
                    "The strong example demonstrates ${pos.second.value.replace('_', ' ')} while the contrast frame shows ${neg.second.value.replace('_', ' ')}",
                    "Both frames have identical composition and exposure",
                    "Neither frame contains EXIF or pixel data",
                ),
                correctIndex = 0,
                examplePhotoUri = pos.first,
                contrastPhotoUri = neg.first,
                explanation = "Spotting the difference in your own shoots builds fast visual intuition in the viewfinder.",
            )
        } else {
            SpotItQuestion(
                prompt = "Next time you are shooting, how can you put '${lesson.title}' into practice?",
                options = listOf(
                    lesson.tryThisPrompt,
                    "Rely exclusively on full Auto mode without checking the viewfinder edges",
                    "Disable the histogram and level overlay permanently",
                ),
                correctIndex = 0,
                examplePhotoUri = positiveMatches.firstOrNull()?.first,
                explanation = lesson.tryThisPrompt,
            )
        }

        return listOf(q1, q2)
    }
}
