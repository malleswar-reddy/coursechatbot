'use client'

interface Message {
  role: 'user' | 'assistant'
  text: string
  meta?: { startPage?: number; endPage?: number; reason?: string }
  isHintOnly?: boolean
  followUpSuggestions?: string[]
}

interface Props {
  message:    Message
  onChipClick:(text: string) => void
}

export default function MessageBubble({ message, onChipClick }: Props) {
  const isUser = message.role === 'user'
  return (
    <div style={isUser ? styles.userBubble : styles.botBubble}>
      <p style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{message.text}</p>

      {message.meta?.startPage !== undefined && (
        <small style={styles.meta}>
          📄 Pages {message.meta.startPage}–{message.meta.endPage}
          {message.meta.reason ? ` · ${message.meta.reason}` : ''}
          {message.isHintOnly && ' · 💡 Hint only'}
        </small>
      )}

      {!isUser && message.followUpSuggestions && message.followUpSuggestions.length > 0 && (
        <div style={styles.chips}>
          {message.followUpSuggestions.map((s, i) => (
            <button key={i} style={styles.chip} onClick={() => onChipClick(s)}>
              {s}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  userBubble: {
    alignSelf: 'flex-end', background: '#1a1a2e', color: '#fff',
    borderRadius: '16px 16px 4px 16px', padding: '10px 14px', maxWidth: '80%',
  },
  botBubble: {
    alignSelf: 'flex-start', background: '#f0f0f0', color: '#1a1a2e',
    borderRadius: '16px 16px 16px 4px', padding: '10px 14px', maxWidth: '80%',
  },
  meta:  { display: 'block', marginTop: 6, color: '#888', fontSize: 12 },
  chips: { display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 10 },
  chip:  {
    background: '#e0e7ff', color: '#3730a3', border: 'none',
    borderRadius: 20, padding: '4px 12px', fontSize: 12,
    cursor: 'pointer', fontWeight: 600,
  },
}

