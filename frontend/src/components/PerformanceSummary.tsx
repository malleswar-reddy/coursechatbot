'use client'

interface Props {
  sessionId:          string
  questionsAttempted: number
  hintCount:          number
  conceptGaps:        string[]
  studyDurationMinutes: number
  onClose:            () => void
}

export default function PerformanceSummary({
  questionsAttempted, hintCount, conceptGaps, studyDurationMinutes, onClose
}: Props) {
  const score = questionsAttempted > 0
    ? Math.round(((questionsAttempted - hintCount) / questionsAttempted) * 100)
    : 0

  return (
    <div style={styles.overlay}>
      <div style={styles.modal}>
        <h2 style={styles.title}>🎓 Session Summary</h2>

        <div style={styles.statsRow}>
          <Stat label="Questions"   value={questionsAttempted} emoji="❓" />
          <Stat label="Hints Used"  value={hintCount}          emoji="💡" />
          <Stat label="Study Time"  value={`${studyDurationMinutes}m`} emoji="⏱️" />
          <Stat label="Confidence"  value={`${score}%`}        emoji="💪" />
        </div>

        {conceptGaps.length > 0 && (
          <div style={styles.gapsBox}>
            <p style={styles.gapsTitle}>📌 Topics to Review:</p>
            <ul style={{ margin: 0, paddingLeft: 20 }}>
              {conceptGaps.map((g, i) => <li key={i} style={{ marginBottom: 4 }}>{g}</li>)}
            </ul>
          </div>
        )}

        {conceptGaps.length === 0 && (
          <p style={{ textAlign: 'center', color: '#22c55e', fontWeight: 600 }}>
            ✅ Great work — no major concept gaps detected!
          </p>
        )}

        <button onClick={onClose} style={styles.closeBtn}>Close &amp; Continue</button>
      </div>
    </div>
  )
}

function Stat({ label, value, emoji }: { label: string; value: string | number; emoji: string }) {
  return (
    <div style={{ textAlign: 'center' }}>
      <div style={{ fontSize: 28 }}>{emoji}</div>
      <div style={{ fontSize: 22, fontWeight: 700 }}>{value}</div>
      <div style={{ fontSize: 12, color: '#666' }}>{label}</div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  overlay: {
    position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.55)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 999,
  },
  modal: {
    background: '#fff', borderRadius: 16, padding: '32px 36px',
    maxWidth: 480, width: '90%', boxShadow: '0 8px 40px rgba(0,0,0,0.2)',
  },
  title:    { margin: '0 0 24px', textAlign: 'center', fontSize: 22, color: '#1a1a2e' },
  statsRow: { display: 'flex', justifyContent: 'space-around', marginBottom: 24 },
  gapsBox:  { background: '#fff8e1', borderRadius: 10, padding: '12px 16px', marginBottom: 20 },
  gapsTitle:{ margin: '0 0 8px', fontWeight: 600, color: '#b45309' },
  closeBtn: {
    display: 'block', width: '100%', padding: '12px', background: '#1a1a2e',
    color: '#fff', border: 'none', borderRadius: 10, cursor: 'pointer',
    fontSize: 15, fontWeight: 600, marginTop: 16,
  },
}

