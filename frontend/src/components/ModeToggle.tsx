'use client'

interface Props {
  value: 'LEARN' | 'EXAM'
  onChange: (mode: 'LEARN' | 'EXAM') => void
}

const MODES: { id: 'LEARN' | 'EXAM'; label: string; tooltip: string }[] = [
  { id: 'LEARN', label: '📖 Learn', tooltip: 'Full explanations with Concept → Formula → Example → Application structure' },
  { id: 'EXAM',  label: '📝 Exam',  tooltip: 'Hints only — no full answers. Test yourself!' },
]

export default function ModeToggle({ value, onChange }: Props) {
  return (
    <div style={styles.wrapper} title={MODES.find(m => m.id === value)?.tooltip}>
      {MODES.map(m => (
        <button
          key={m.id}
          onClick={() => onChange(m.id)}
          title={m.tooltip}
          style={{
            ...styles.btn,
            ...(value === m.id ? styles.active : styles.inactive),
          }}
        >
          {m.label}
        </button>
      ))}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  wrapper: { display: 'flex', borderRadius: 8, overflow: 'hidden', border: '1px solid #444' },
  btn:     { padding: '6px 14px', border: 'none', cursor: 'pointer', fontSize: 13, fontWeight: 600, transition: 'all 0.15s' },
  active:  { background: '#e2f0ff', color: '#1a1a2e' },
  inactive:{ background: 'transparent', color: '#ccc' },
}

