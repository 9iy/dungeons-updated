# Create a README.md with the requested details for the user's mod
readme = r"""# Dungeons (Fabric 1.21.1 • Kotlin)

A lightweight party + GUI scaffold for your dungeon mod. This README documents **what exists now** and **what you asked for**, so it’s easy to extend as we add dungeon features.

---

## What’s implemented now

### Party System (server-side, in-memory)
- **Create party** (max **15** parties, **4** members per party).
- Party name: **`<LeaderName>'s Party`** (e.g. “Alex’s Party”).  
- **Public or Private** toggle on create:
  - `/dungeon party create public`
  - `/dungeon party create private` (default if omitted)
- **Invites** with GUI:
  - `/dungeon party invite <player>` to invite.
  - `/dungeon party invites` opens a chest GUI listing pending invitations (oldest→newest).  
    - **Left-click** head = accept.
    - **Right-click** head = decline.
- **Disband**:
  - `/dungeon party disband` (leader-only). Disbands the in-memory party.
- **Kick**:
  - **Leader-only**: **right‑click** a member’s head in the Party GUI to kick.
- **Ready‑up system**:
  - Each member has a personal **ready toggle** under their head (owner-only).
  - **Green head** (Trajan) = READY, **Red head** (Rui_Er) = NOT READY.
  - Toggles play a **local sound only to the clicker**:
    - READY → **Iron Xylophone** (`note block iron`)
    - UNREADY → **Guitar** (`note block guitar`)

> State is held **in memory**; it resets on server restart (persistence is a future task).

### GUIs (single-chest, 27 slots; items cannot be moved)
All custom menus forbid taking/moving the GUI items; clicks are used as buttons.

#### Main Menu — `/dungeon open`
Uses 1–27 slot numbering (top-left = 1, bottom-right = 27):
- **11**: *Dungeon Select* (Cyan Dye) — opens dungeon type selector
- **12**: *Party Invites* (Book) — opens the Invites GUI
- **15**: *Dungeon Stats* (Yellow Dye) — *placeholder*
- **16**: *Party System* (Purple Dye) — opens the Party GUI
- **17**: *Public Parties* (Nether Star) — shows the public party browser

#### Party GUI — `/dungeon party`
- Heads row: **11, 13, 14, 15** → Leader first, then members by join order.
- Ready icons row (under each head): **20, 22, 23, 24** → only the owner can toggle their own.
- **Right‑click a non‑self head** (leader only) = kick that player.
- Slot **10** shows a *start* checkmark once everyone is ready **and** a dungeon type is selected. Leader click starts the run.

#### Invites GUI — `/dungeon party invites`
- Shows invites in slots **1..27**, oldest→newest as player heads.
- **Left-click**: accept invite. **Right-click**: decline invite. The list compacts after each action.

#### Public Parties Browser — from Main Menu (slot 18)
- Lists **public** parties only (private parties are hidden).
- Each entry shows:
  - **Host Head** (leader skin & name)
  - Lore with 3 lines:
    - `- MemberName` or `- ---` if empty slot
  - A bottom line of either **`[JOIN!]`** or **`[FULL]`**
- **Click** an entry to attempt to join (must not already be in a party, and party must have space).

### Player Heads (1.21 data components)
- Heads use `minecraft:profile` (`DataComponentTypes.PROFILE`) with **non-null UUIDs**:
  - For online/cached players: their real profile.
  - Otherwise: deterministic **OfflinePlayer** UUID for the given name.
- Result: heads render consistently without NPEs on authlib 6.x.
- The two ready icons use the profiles **`Trajan`** (READY) and **`Rui_Er`** (NOT READY).

### CarbonChat Integration (optional)
When a party is created/disbanded, we also run commands as the **leader**:
- On create: `/party create DungeonParty(<LeaderName>)`
- On disband: `/party disband`
> If CarbonChat isn’t installed, these are harmless no-ops.

---

**Rules & Limits**
- Max **15** parties total (IDs 1..15).
- Max **4** members per party (leader + 3).
- Leader disconnecting → disbands party. Member disconnecting → leaves party.

---

## What you asked for (roadmap / TODO)

- **Dungeon Stats** view (slot 15).
- **Leave Party** command/button for non-leaders.
- **Transfer Leadership** (GUI button or command).
- **Party privacy toggle after creation** (currently set only at create-time).
- **Persistent storage** (save parties/ready state across restarts).
- **Invite expiration** (optional timeouts).

---
## Commands (current)

`/dungeon dcreate <name> <type> <corner1> <corner2>`
: Register a dungeon region between two corners. The type must match a dungeon type created via `/dungeons type create`; the selector icon comes from that type’s display config. **Op only**.

