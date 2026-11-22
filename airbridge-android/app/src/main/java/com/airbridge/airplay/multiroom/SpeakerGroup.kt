package com.airbridge.airplay.multiroom

import com.airbridge.airplay.util.Hex

/** A saved set of speakers (a preset such as "Downstairs"), identified by receiver ids. */
data class SpeakerGroup(val id: String, val name: String, val receiverIds: Set<String>)

/**
 * Storage format for saved groups. Every string (id, name, member ids) is stored as lowercase hex
 * of its UTF-8 bytes, so any text, including Unicode, quotes, pipes, commas, colons and line
 * breaks, round-trips exactly and can never be confused with a separator:
 *
 * ```
 * v1
 * <hex id>:<hex name>:<hex member>,<hex member>,...
 * ```
 *
 * One line per group, in order. [decode] skips lines it cannot read instead of failing.
 */
object SpeakerGroupCodec {
    private const val HEADER = "v1"

    fun encode(groups: List<SpeakerGroup>): String {
        val sb = StringBuilder(HEADER)
        for (g in groups) {
            sb.append('\n').append(hex(g.id)).append(':').append(hex(g.name)).append(':')
            g.receiverIds.joinTo(sb, ",") { hex(it) }
        }
        return sb.toString()
    }

    fun decode(raw: String?): List<SpeakerGroup> {
        if (raw.isNullOrEmpty()) return emptyList()
        val lines = raw.split('\n')
        if (lines.first() != HEADER) return emptyList()
        val out = ArrayList<SpeakerGroup>()
        for (line in lines.drop(1)) {
            val parts = line.split(':')
            if (parts.size != 3) continue
            val id = unhex(parts[0]) ?: continue
            val name = unhex(parts[1]) ?: continue
            val members = if (parts[2].isEmpty()) emptyList() else parts[2].split(',').map { unhex(it) }
            if (members.any { it == null }) continue
            out += SpeakerGroup(id, name, members.filterNotNull().toCollection(LinkedHashSet()))
        }
        return out
    }

    private fun hex(s: String): String = Hex.encode(s.toByteArray(Charsets.UTF_8))

    private fun unhex(h: String): String? {
        if (h.length % 2 != 0 || h.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return String(Hex.decode(h), Charsets.UTF_8)
    }
}

/** Result of saving or renaming a group. */
sealed interface GroupChange {
    /** [replaced] is true when an existing group with the same name got the new speakers. */
    data class Saved(val groups: List<SpeakerGroup>, val group: SpeakerGroup, val replaced: Boolean) : GroupChange

    data class Rejected(val reason: String) : GroupChange
}

/** Pure operations on the saved-group list (create, update, rename, delete, apply, match). */
object SpeakerGroups {
    const val MAX_NAME_LENGTH = 40
    const val MAX_GROUPS = 24

    /**
     * Cleans a group name typed by the user: line breaks, tabs and other control characters become
     * spaces, runs of spaces collapse, the ends are trimmed and the result is cut to
     * [MAX_NAME_LENGTH] characters without splitting an emoji. Returns null when nothing is left.
     */
    fun cleanName(raw: String): String? {
        val sb = StringBuilder()
        var lastSpace = true
        var count = 0
        var i = 0
        while (i < raw.length && count < MAX_NAME_LENGTH) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isISOControl(cp) || Character.isSpaceChar(cp)) {
                if (!lastSpace) {
                    sb.append(' ')
                    count++
                    lastSpace = true
                }
                continue
            }
            sb.appendCodePoint(cp)
            count++
            lastSpace = false
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun sameName(a: String, b: String) = a.trim().equals(b.trim(), ignoreCase = true)

    fun findByName(groups: List<SpeakerGroup>, name: String): SpeakerGroup? {
        val clean = cleanName(name) ?: return null
        return groups.firstOrNull { sameName(it.name, clean) }
    }

    /**
     * Saves [members] under [name]. A group with the same name (ignoring case) gets the new
     * speakers and keeps its id and position; otherwise a new group is added at the end.
     */
    fun save(groups: List<SpeakerGroup>, name: String, members: Set<String>, newId: () -> String): GroupChange {
        val clean = cleanName(name) ?: return GroupChange.Rejected("Enter a name for the group")
        if (members.isEmpty()) return GroupChange.Rejected("Turn on at least one speaker first")
        val existing = groups.firstOrNull { sameName(it.name, clean) }
        if (existing != null) {
            val updated = existing.copy(name = clean, receiverIds = LinkedHashSet(members))
            return GroupChange.Saved(groups.map { if (it.id == existing.id) updated else it }, updated, replaced = true)
        }
        if (groups.size >= MAX_GROUPS) return GroupChange.Rejected("You can save up to $MAX_GROUPS groups")
        var id = newId()
        while (id.isBlank() || groups.any { it.id == id }) id = newId()
        val created = SpeakerGroup(id, clean, LinkedHashSet(members))
        return GroupChange.Saved(groups + created, created, replaced = false)
    }

    fun rename(groups: List<SpeakerGroup>, id: String, newName: String): GroupChange {
        val target = groups.firstOrNull { it.id == id } ?: return GroupChange.Rejected("That group no longer exists")
        val clean = cleanName(newName) ?: return GroupChange.Rejected("Enter a name for the group")
        if (groups.any { it.id != id && sameName(it.name, clean) }) return GroupChange.Rejected("Another group already has that name")
        val renamed = target.copy(name = clean)
        return GroupChange.Saved(groups.map { if (it.id == id) renamed else it }, renamed, replaced = true)
    }

    fun delete(groups: List<SpeakerGroup>, id: String): List<SpeakerGroup> = groups.filterNot { it.id == id }

    /**
     * Drops entries that cannot be used: blank id or name, no members, or a repeated id (the first
     * one wins). Applied to whatever was read from storage.
     */
    fun normalize(groups: List<SpeakerGroup>): List<SpeakerGroup> {
        val seen = HashSet<String>()
        return groups.filter { g ->
            g.id.isNotBlank() && g.name.isNotBlank() && g.receiverIds.isNotEmpty() && seen.add(g.id)
        }
    }

    /** Speakers to turn on for [group]: its members that are available now. Offline members are skipped. */
    fun selectionFor(group: SpeakerGroup, available: Set<String>): Set<String> =
        group.receiverIds.filterTo(LinkedHashSet()) { it in available }

    /** Members of [group] that are not available now. */
    fun offlineMembers(group: SpeakerGroup, available: Set<String>): Set<String> =
        group.receiverIds.filterTo(LinkedHashSet()) { it !in available }

    /**
     * True when the speakers switched on (among those available) are exactly the group's available
     * members. Offline members do not prevent a match; a group with no member available never matches.
     */
    fun isActive(group: SpeakerGroup, selected: Set<String>, available: Set<String>): Boolean {
        val members = selectionFor(group, available)
        return members.isNotEmpty() && members == selected.filterTo(HashSet()) { it in available }
    }
}
