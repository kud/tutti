package io.kud.salon.protocol.scenes

import io.kud.salon.protocol.scenes.SceneValue.Flag
import io.kud.salon.protocol.scenes.SceneValue.Level
import io.kud.salon.protocol.scenes.SceneValue.Text

/**
 * The starter gallery. Names are French on purpose: they are user data once installed,
 * editable like any scene. Picture mode values (`cinema`, `game`, `standard`) follow Sony's
 * documented candidates and are unverified on the XR80; the editor offers the TV's own list.
 */
object StarterScenes {
    val all: List<Scene> = listOf(
        Scene(
            id = "starter-cinema",
            name = "Cinéma",
            color = 0xFF8E5CF2,
            glyph = "movie",
            settings = mapOf(
                SceneKey.PICTURE_MODE to Text("cinema"),
                SceneKey.SOUND_FIELD to Flag(true),
                SceneKey.VOICE to Flag(false),
                SceneKey.NIGHT to Flag(false),
            ),
        ),
        Scene(
            id = "starter-serie",
            name = "Série du soir",
            color = 0xFF5C7CF2,
            glyph = "tv",
            settings = mapOf(
                SceneKey.SOUND_FIELD to Flag(true),
                SceneKey.VOICE to Flag(true),
                SceneKey.NIGHT to Flag(false),
            ),
        ),
        Scene(
            id = "starter-nuit",
            name = "Nuit",
            color = 0xFF3B4A6B,
            glyph = "bedtime",
            settings = mapOf(
                SceneKey.NIGHT to Flag(true),
                SceneKey.VOICE to Flag(true),
                SceneKey.VOLUME_CAP to Level(20),
            ),
        ),
        Scene(
            id = "starter-musique",
            name = "Musique",
            color = 0xFFE0609A,
            glyph = "music_note",
            settings = mapOf(
                SceneKey.SOUND_FIELD to Flag(true),
                SceneKey.VOICE to Flag(false),
                SceneKey.NIGHT to Flag(false),
            ),
        ),
        Scene(
            id = "starter-sport",
            name = "Sport",
            color = 0xFF3FAE6A,
            glyph = "sports_soccer",
            settings = mapOf(
                SceneKey.PICTURE_MODE to Text("standard"),
                SceneKey.SOUND_FIELD to Flag(true),
                SceneKey.VOICE to Flag(true),
            ),
        ),
        Scene(
            id = "starter-jeu",
            name = "Jeu",
            color = 0xFFF2994A,
            glyph = "sports_esports",
            settings = mapOf(
                SceneKey.PICTURE_MODE to Text("game"),
                SceneKey.NIGHT to Flag(false),
            ),
        ),
        Scene(
            id = "starter-info",
            name = "Info",
            color = 0xFF9AA5B1,
            glyph = "newspaper",
            settings = mapOf(
                SceneKey.SOUND_FIELD to Flag(false),
                SceneKey.VOICE to Flag(true),
            ),
        ),
    )
}
