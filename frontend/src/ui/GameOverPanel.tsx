import { useGameStore } from '../state/useGameStore'
import styles from './GameOverPanel.module.css'

/**
 * Full-screen modal displayed when the server sends a GAME_OVER ConnectionStatus.
 *
 * Shows the player's final health score, average health, and the total simulated
 * grid-time managed. The "Play again" button disconnects the session and reloads
 * the page so the bootstrap flow creates a fresh session.
 *
 * Hidden when `gameOver` is null (normal play).
 * When `gameOver.won === true`, shows a Challenge victory banner instead of the
 * defeat banner.
 */
export function GameOverPanel() {
  const gameOver = useGameStore((s) => s.gameOver)
  const disconnect = useGameStore((s) => s.disconnect)

  if (!gameOver) return null

  const { finalHealthScore, averageHealthScore, gridTimeManagedMinutes, won } = gameOver
  const gridHours = Math.floor(gridTimeManagedMinutes / 60)
  const gridMins  = gridTimeManagedMinutes % 60

  function scoreClass(score: number) {
    if (score >= 60) return styles.scoreGood
    if (score >= 30) return styles.scoreWarning
    return styles.scoreCritical
  }

  function handlePlayAgain() {
    disconnect()
    // Reload causes useSessionBootstrap to create a fresh session.
    window.location.reload()
  }

  return (
    <div className={styles.overlay} data-testid="game-over-overlay">
      <div className={`${styles.panel} ${won ? styles.panelWon : ''}`} data-testid="game-over-panel">
        <div className={styles.header}>
          {won ? (
            <>
              <h2 className={`${styles.title} ${styles.titleWon}`}>Challenge Complete!</h2>
              <p className={styles.subtitle}>
                Grid stability restored — well done, operator.
              </p>
            </>
          ) : (
            <>
              <h2 className={styles.title}>Grid Failure</h2>
              <p className={styles.subtitle}>
                Health remained critically low for too long — the grid has collapsed.
              </p>
            </>
          )}
        </div>

        <div className={styles.stats}>
          <div className={styles.stat}>
            <div className={`${styles.statValue} ${scoreClass(finalHealthScore)}`}>
              {finalHealthScore}
            </div>
            <div className={styles.statLabel}>Final health</div>
          </div>

          <div className={styles.stat}>
            <div className={`${styles.statValue} ${scoreClass(averageHealthScore)}`}>
              {averageHealthScore}
            </div>
            <div className={styles.statLabel}>Avg health</div>
          </div>

          <div className={styles.stat} style={{ gridColumn: '1 / -1' }}>
            <div className={styles.statValue} style={{ color: '#60a5fa' }}>
              {gridHours}h {String(gridMins).padStart(2, '0')}m
            </div>
            <div className={styles.statLabel}>Grid time managed</div>
          </div>
        </div>

        <div className={styles.actions}>
          <button className={styles.btn} onClick={handlePlayAgain} data-testid="game-over-play-again">
            Play Again
          </button>
        </div>
      </div>
    </div>
  )
}
