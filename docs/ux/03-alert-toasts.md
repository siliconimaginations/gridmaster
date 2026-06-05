# UX: Alert Toasts

**Stage**: 1 | **Status**: Draft

---

## Purpose

Non-blocking notifications that surface events and violations without pulling the player away from the map. Stacked in the bottom-right corner.

---

## Toast anatomy

```
┌─ [border colour] ──────────────────┐
│ [emoji] Short message text      [×]│
│         Secondary context (opt.)   │
└────────────────────────────────────┘
```

- Width: 200px fixed
- Max 3 toasts visible; 4th+ queued (slide in as older ones dismiss)
- Tapping the toast body opens the relevant detail panel

---

## Severity levels

| Severity | Border | Stays until | Emoji |
|----------|--------|-------------|-------|
| Critical | Red | Player acts | ⚡ ⛔ 🔥 |
| Warning | Amber | 60 game-min auto-dismiss | ⚠️ 🌡️ 🌩️ |
| Info | Blue | 30 game-min auto-dismiss | ℹ️ 📊 📅 |

---

## Toast types

| Type | Example text | Opens |
|------|-------------|-------|
| N-1 violation | ⚡ CCGT-1 loss overloads Line L3 | N-1 panel |
| Line overload | 🌡️ Sub A→B at 87% — near limit | Inspector |
| Weather event | 🌩️ Storm — East region, wind +40% in 2h | Event panel |
| Fuel event | 💰 Gas price spike · +35% for 6h | Event panel |
| Policy card | 📋 New: renewable subsidy offer | Event card |
| UC reminder | 📅 Day-ahead commitment due in 3h | Plan panel |
| Network failure | ⛔ Grid failure — Northgate islanded | Restore panel |

---

## Open questions

None.
