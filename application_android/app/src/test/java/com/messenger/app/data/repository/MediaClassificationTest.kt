package com.messenger.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server sets `file_type` from the message's content type on EVERY message, so an ordinary
 * text message arrives carrying `file_type = "text"`. Classifying anything non-blank as media
 * therefore marked every text message as an attachment, and `cacheMessages` skipped its entire
 * decrypt-on-store block for them: cache-first lookup, the MLS decrypt, archive restore, and
 * archiving itself all became unreachable for ordinary text.
 *
 * These pin the discriminator to the four known media content types - the same set the UI already
 * uses in `toChatMessageUi`.
 */
class MediaClassificationTest {

    /** The regression itself: "text" is what every ordinary message carries. */
    @Test
    fun textIsNotMedia() {
        assertFalse(
            "an ordinary text message must not be classified as an attachment",
            isMediaContentType("text")
        )
    }

    /** Every real attachment type must still be classified as media. */
    @Test
    fun everyKnownMediaTypeIsMedia() {
        assertTrue(isMediaContentType(ChatRepository.VOICE_CONTENT_TYPE))
        assertTrue(isMediaContentType(ChatRepository.IMAGE_CONTENT_TYPE))
        assertTrue(isMediaContentType(ChatRepository.FILE_CONTENT_TYPE))
        assertTrue(isMediaContentType(ChatRepository.VIDEO_NOTE_CONTENT_TYPE))
    }

    /** Those constants are the wire values; pinning them stops a silent rename from re-breaking this. */
    @Test
    fun mediaTypeWireValuesAreStable() {
        assertTrue(isMediaContentType("audio"))
        assertTrue(isMediaContentType("image"))
        assertTrue(isMediaContentType("file"))
        assertTrue(isMediaContentType("video_note"))
    }

    /** Absent or blank metadata is ordinary text, never an attachment. */
    @Test
    fun blankOrAbsentMetadataIsNotMedia() {
        assertFalse(isMediaContentType(null))
        assertFalse(isMediaContentType(""))
        assertFalse(isMediaContentType("   "))
    }

    /**
     * An unknown type is treated as text, deliberately. Guessing "media" would drop the row out of
     * caching entirely - exactly the failure this phase fixed - whereas treating it as text merely
     * attempts a decrypt that can fail harmlessly.
     */
    @Test
    fun anUnknownTypeFallsBackToText() {
        assertFalse(isMediaContentType("sticker"))
        assertFalse(isMediaContentType("TEXT"))
    }
}
