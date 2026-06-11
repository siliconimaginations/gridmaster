import { useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import type { EventCardDto, EventOptionDto } from '../api/types'
import { useGameStore } from '../state/useGameStore'
import styles from './EventCardPanel.module.css'

// ── Constants ─────────────────────────────────────────────────────────────────

const SEVERITY_LABEL: Record<EventCardDto['severity'], string> = {
  CRITICAL: '⚡ Critical Event',
  WARNING: '⚠️ Warning Event',
  INFO: 'ℹ️ Event',
}

// ── EventCardPanel ────────────────────────────────────────────────────────────

/**
 * Blocking modal panel that presents the first pending event card from the
 * Zustand store. The player must choose one option and press Apply — there is
 * no dismiss/close path, by design. Sends a `RespondToEventCard` command over
 * WebSocket when the player applies their choice.
 *
 * Renders nothing when `pendingEventCards` is empty.
 *
 * @see issue #87
 */
export function EventCardPanel() {
  const pendingEventCards = useGameStore(useShallow((s) => s.pendingEventCards))
  const sendCommand = useGameStore((s) => s.sendCommand)

  const [selectedOptionId, setSelectedOptionId] = useState<string | null>(null)

  const card = pendingEventCards[0] ?? null

  if (!card) return null

  const handleApply = () => {
    if (!selectedOptionId) return
    sendCommand({
      commandType: 'RespondToEventCard',
      payload: { cardId: card.id, optionId: selectedOptionId },
    })
    setSelectedOptionId(null)
  }

  return (
    <div className={styles.overlay} data-testid="event-card-overlay" key={card.id}>
      <div
        className={`${styles.panel} ${styles[`panel_${card.severity.toLowerCase()}`]}`}
        data-testid="event-card-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="event-card-title"
      >
        {/* Header */}
        <div className={`${styles.header} ${styles[`header_${card.severity.toLowerCase()}`]}`}>
          <span className={styles.severityLabel}>{SEVERITY_LABEL[card.severity]}</span>
        </div>

        {/* Title + description */}
        <div className={styles.body}>
          <p id="event-card-title" className={styles.title}>{card.title}</p>
          <p className={styles.description}>{card.description}</p>
        </div>

        {/* Options */}
        <ul className={styles.options} role="listbox" aria-label="Response options">
          {card.options.map((opt) => (
            <OptionCard
              key={opt.id}
              option={opt}
              selected={selectedOptionId === opt.id}
              onSelect={() => setSelectedOptionId(opt.id)}
            />
          ))}
        </ul>

        {/* Apply */}
        <div className={styles.footer}>
          <button
            className={styles.applyBtn}
            onClick={handleApply}
            disabled={!selectedOptionId}
            data-testid="event-card-apply"
          >
            Apply
          </button>
        </div>
      </div>
    </div>
  )
}

// ── OptionCard ────────────────────────────────────────────────────────────────

interface OptionCardProps {
  option: EventOptionDto
  selected: boolean
  onSelect: () => void
}

/** Single selectable option within an event card. */
function OptionCard({ option, selected, onSelect }: OptionCardProps) {
  return (
    <li
      className={`${styles.option} ${selected ? styles.optionSelected : ''}`}
      role="option"
      aria-selected={selected}
      onClick={onSelect}
      data-testid={`event-option-${option.id}`}
    >
      <span className={styles.optionLabel}>{option.label}</span>
      <div className={styles.optionMeta}>
        <span className={styles.optionTag}>{option.tag}</span>
        <span className={styles.optionCost}>£{option.costGbp.toLocaleString()}</span>
      </div>
    </li>
  )
}
