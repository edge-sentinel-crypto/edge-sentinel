package com.airbridge.airplay.multiroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved speaker groups: storage round trips with awkward text, CRUD rules, offline members, matching. */
class SpeakerGroupTest {
    private var nextId = 0
    private val ids: () -> String = { "g${++nextId}" }

    private fun saved(change: GroupChange): GroupChange.Saved {
        assertTrue("expected Saved, got $change", change is GroupChange.Saved)
        return change as GroupChange.Saved
    }

    @Test
    fun codecRoundTripsUnicodeQuotesPipesCommasAndSeparators() {
        val groups = listOf(
            SpeakerGroup("g1", "Downstairs", linkedSetOf("A1B2C3D4E5F6", "7828CAC3238C")),
            SpeakerGroup("g2", "Küche & Café ☕ 日本語 🎶👨‍👩‍👧", linkedSetOf("manual:192.0.2.5:7000")),
            SpeakerGroup("g3", "He said \"loud\", it's 'fine' | a,b:c;d\\e", linkedSetOf("id|with|pipes", "id,with,commas", "id:with:colons")),
            SpeakerGroup("g|4,\"x\"", "line\nbreak\ttab\r\n", linkedSetOf("", "x\u0000y", "\uD83D\uDE00")),
            SpeakerGroup("g5", "", linkedSetOf("solo")),
            SpeakerGroup("g6", "No members", emptySet()),
        )
        val text = SpeakerGroupCodec.encode(groups)
        assertTrue("stored text uses only hex digits and separators", text.removePrefix("v1").all { it in "0123456789abcdef:,\n" })
        assertEquals(groups, SpeakerGroupCodec.decode(text))
        // Member order is kept too.
        assertEquals(listOf("A1B2C3D4E5F6", "7828CAC3238C"), SpeakerGroupCodec.decode(text)[0].receiverIds.toList())
        assertEquals("v1", SpeakerGroupCodec.encode(emptyList()))
        assertEquals(emptyList<SpeakerGroup>(), SpeakerGroupCodec.decode("v1"))
    }

    @Test
    fun decodeSkipsDamagedLinesInsteadOfFailing() {
        val good = SpeakerGroupCodec.encode(listOf(SpeakerGroup("g1", "Patio", setOf("a", "b"))))
        val damaged = good + "\nzz:41:42\n4142\n41:42:4,43\n:::\n41:42:43:44\n" + good.substringAfter('\n')
        val decoded = SpeakerGroupCodec.decode(damaged)
        assertEquals(2, decoded.size)
        assertEquals(SpeakerGroup("g1", "Patio", setOf("a", "b")), decoded[0])
        assertEquals(emptyList<SpeakerGroup>(), SpeakerGroupCodec.decode(null))
        assertEquals(emptyList<SpeakerGroup>(), SpeakerGroupCodec.decode(""))
        assertEquals("unknown format version", emptyList<SpeakerGroup>(), SpeakerGroupCodec.decode("v9\n" + good.substringAfter('\n')))
        assertEquals("upper-case hex is not this format", emptyList<SpeakerGroup>(), SpeakerGroupCodec.decode("v1\n4A:4B:4C"))
        // Duplicate ids, blank names and empty groups read from storage are dropped (first id wins).
        val normalized = SpeakerGroups.normalize(decoded + listOf(SpeakerGroup("g2", " ", setOf("a")), SpeakerGroup("g3", "Empty", emptySet())))
        assertEquals(listOf(SpeakerGroup("g1", "Patio", setOf("a", "b"))), normalized)
    }

    @Test
    fun inMemoryStoreKeepsGroupsAsEncodedText() {
        val store = InMemorySpeakerStore()
        val groups = listOf(SpeakerGroup("g1", "Wohnzimmer \"oben\" | 2,5 ✓", setOf("x", "y")))
        store.setSpeakerGroups(groups)
        assertEquals(groups, store.speakerGroups())
        assertFalse("raw text never contains the name", store.rawGroups()!!.contains("Wohnzimmer"))
    }

    @Test
    fun createUpdateRenameDelete() {
        var groups = emptyList<SpeakerGroup>()
        val first = saved(SpeakerGroups.save(groups, "  Downstairs  ", linkedSetOf("kitchen", "living"), ids))
        assertFalse(first.replaced)
        assertEquals(SpeakerGroup("g1", "Downstairs", setOf("kitchen", "living")), first.group)
        groups = first.groups
        groups = saved(SpeakerGroups.save(groups, "Whole home", setOf("kitchen", "living", "den"), ids)).groups
        assertEquals(listOf("Downstairs", "Whole home"), groups.map { it.name })

        // Same name, other case: the existing group gets the new speakers, keeps its id and place.
        val updated = saved(SpeakerGroups.save(groups, "DOWNSTAIRS", setOf("den"), ids))
        assertTrue(updated.replaced)
        assertEquals(listOf("g1", "g2"), updated.groups.map { it.id })
        assertEquals(setOf("den"), updated.groups[0].receiverIds)
        assertEquals("DOWNSTAIRS", updated.groups[0].name)
        groups = updated.groups

        // Rename, then refuse a rename onto another group's name and blank names.
        groups = saved(SpeakerGroups.rename(groups, "g1", "Den only")).groups
        assertEquals("Den only", groups[0].name)
        assertTrue(SpeakerGroups.rename(groups, "g1", "whole HOME") is GroupChange.Rejected)
        assertTrue(SpeakerGroups.rename(groups, "g1", "   ") is GroupChange.Rejected)
        assertTrue(SpeakerGroups.rename(groups, "nope", "X") is GroupChange.Rejected)
        assertEquals("renaming a group to its own name in other case is fine", "den ONLY", saved(SpeakerGroups.rename(groups, "g1", "den ONLY")).group.name)

        groups = SpeakerGroups.delete(groups, "g1")
        assertEquals(listOf("g2"), groups.map { it.id })
        assertEquals(groups, SpeakerGroups.delete(groups, "missing"))
    }

    @Test
    fun rejectsEmptyNamesAndEmptyGroupsAndCapsTheCount() {
        assertTrue(SpeakerGroups.save(emptyList(), "", setOf("a"), ids) is GroupChange.Rejected)
        assertTrue(SpeakerGroups.save(emptyList(), " \n\t ", setOf("a"), ids) is GroupChange.Rejected)
        assertTrue(SpeakerGroups.save(emptyList(), "Patio", emptySet(), ids) is GroupChange.Rejected)
        var groups = emptyList<SpeakerGroup>()
        repeat(SpeakerGroups.MAX_GROUPS) { i -> groups = saved(SpeakerGroups.save(groups, "Group $i", setOf("a"), ids)).groups }
        assertTrue(SpeakerGroups.save(groups, "One more", setOf("a"), ids) is GroupChange.Rejected)
        assertTrue("updating an existing name still works at the cap", SpeakerGroups.save(groups, "group 3", setOf("b"), ids) is GroupChange.Saved)
    }

    @Test
    fun duplicateIdsFromTheGeneratorAreSkipped() {
        val existing = listOf(SpeakerGroup("same", "A", setOf("a")))
        val seq = ArrayDeque(listOf("same", "", "fresh"))
        val created = saved(SpeakerGroups.save(existing, "B", setOf("b"), { seq.removeFirst() })).group
        assertEquals("fresh", created.id)
    }

    @Test
    fun cleansNames() {
        assertEquals("Downstairs", SpeakerGroups.cleanName("  Downstairs "))
        assertEquals("Living room left", SpeakerGroups.cleanName("Living\nroom \t  left"))
        assertNull(SpeakerGroups.cleanName("   "))
        assertNull(SpeakerGroups.cleanName(""))
        assertEquals("Café \"Ö\" | ✓, 'x'", SpeakerGroups.cleanName("Café \"Ö\" | ✓, 'x'"))
        val long = "🎶".repeat(60)
        val cut = SpeakerGroups.cleanName(long)!!
        assertEquals("40 characters, no split emoji", 40, cut.codePointCount(0, cut.length))
        assertEquals("🎶".repeat(40), cut)
        assertEquals(40, SpeakerGroups.cleanName("x".repeat(100))!!.length)
    }

    @Test
    fun applyingAGroupSkipsOfflineMembersAndMatchesTheSelection() {
        val group = SpeakerGroup("g1", "Whole home", linkedSetOf("kitchen", "living", "garage"))
        val available = setOf("kitchen", "living", "den") // garage is offline
        assertEquals(setOf("kitchen", "living"), SpeakerGroups.selectionFor(group, available))
        assertEquals(setOf("garage"), SpeakerGroups.offlineMembers(group, available))

        assertTrue("selection equals available members", SpeakerGroups.isActive(group, setOf("kitchen", "living"), available))
        assertTrue("a selected but offline speaker does not matter", SpeakerGroups.isActive(group, setOf("kitchen", "living", "garage", "attic"), available))
        assertFalse("an extra speaker is on", SpeakerGroups.isActive(group, setOf("kitchen", "living", "den"), available))
        assertFalse("a member is off", SpeakerGroups.isActive(group, setOf("kitchen"), available))
        val gone = SpeakerGroup("g2", "Garage", setOf("garage"))
        assertEquals(emptySet<String>(), SpeakerGroups.selectionFor(gone, available))
        assertFalse("no member available: never active", SpeakerGroups.isActive(gone, emptySet(), available))
    }
}
