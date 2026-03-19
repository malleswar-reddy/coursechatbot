'use client'

type Difficulty = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'

interface Props {
  value: Difficulty
  onChange: (d: Difficulty) => void
}

const LEVELS: { id: Difficulty; label: string; color: string }[] = [
  { id: 'BEGINNER',     label: '🟢 Beginner',    color: '#22c55e' },
  { id: 'INTERMEDIATE', label: '🟡 Intermediate', color: '#eab308' },
  { id: 'ADVANCED',     label: '🔴 Advanced',     color: '#ef4444' },
]

export default function DifficultySelector({ value, onChange }: Props) {
  return (
    <div style={{ display: 'flex', gap: 6 }}>
      {LEVELS.map(l => (
        <button
          key={l.id}
          onClick={() => onChange(l.id)}
          style={{
            padding: '4px 12px',
            border: `2px solid ${value === l.id ? l.color : 'transparent'}`,
            borderRadius: 20,
            cursor: 'pointer',
            fontSize: 12,
            fontWeight: 600,
            color:      value === l.id ? l.color : '#aaa',
            background: value === l.id ? 'rgba(255,255,255,0.07)' : 'transparent',
            transition: 'all 0.15s',
          }}
        >
          {l.label}
        </button>
      ))}
    </div>
  )
}

