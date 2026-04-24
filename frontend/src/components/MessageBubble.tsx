'use client'

import ReactMarkdown from 'react-markdown'
import remarkGfm    from 'remark-gfm'
import type { Components } from 'react-markdown'

interface Message {
  role: 'user' | 'assistant'
  text: string
  streaming?: boolean
  meta?: { startPage?: number; endPage?: number; reason?: string }
  isHintOnly?: boolean
  followUpSuggestions?: string[]
}

interface Props {
  message:     Message
  onChipClick: (text: string) => void
}

// ── Custom markdown components — styled inline ────────────────────────────────
const mdComponents: Components = {
  // Headings
  h1: ({ children }) => <h1 style={{ fontSize: 18, fontWeight: 700, margin: '12px 0 4px', color: '#1a1a2e' }}>{children}</h1>,
  h2: ({ children }) => <h2 style={{ fontSize: 16, fontWeight: 700, margin: '10px 0 4px', color: '#1a1a2e' }}>{children}</h2>,
  h3: ({ children }) => <h3 style={{ fontSize: 14, fontWeight: 700, margin: '8px 0 4px', color: '#1a1a2e' }}>{children}</h3>,

  // Paragraphs — proper spacing
  p: ({ children }) => <p style={{ margin: '6px 0', lineHeight: 1.65 }}>{children}</p>,

  // Bold / Italic
  strong: ({ children }) => <strong style={{ fontWeight: 700, color: '#0f172a' }}>{children}</strong>,
  em:     ({ children }) => <em style={{ fontStyle: 'italic', color: '#374151' }}>{children}</em>,

  // Numbered & bullet lists
  ol: ({ children }) => <ol style={{ paddingLeft: 20, margin: '8px 0', lineHeight: 1.7 }}>{children}</ol>,
  ul: ({ children }) => <ul style={{ paddingLeft: 20, margin: '8px 0', lineHeight: 1.7 }}>{children}</ul>,
  li: ({ children }) => <li style={{ marginBottom: 4 }}>{children}</li>,

  // Inline code / code blocks
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  code: ({ inline, children, ...props }: any) =>
    inline
      ? <code style={{ background: '#e2e8f0', borderRadius: 4, padding: '1px 5px', fontSize: 13, fontFamily: 'monospace' }} {...props}>{children}</code>
      : <pre style={{ background: '#1e293b', color: '#e2e8f0', borderRadius: 8, padding: '10px 14px', overflowX: 'auto', fontSize: 13, fontFamily: 'monospace', margin: '8px 0' }}><code {...props}>{children}</code></pre>,

  // Horizontal rule
  hr: () => <hr style={{ border: 'none', borderTop: '1px solid #e2e8f0', margin: '10px 0' }} />,

  // Blockquote
  blockquote: ({ children }) => (
    <blockquote style={{ borderLeft: '3px solid #6366f1', paddingLeft: 12, margin: '8px 0', color: '#4b5563', fontStyle: 'italic' }}>
      {children}
    </blockquote>
  ),
}

export default function MessageBubble({ message, onChipClick }: Props) {
  const isUser = message.role === 'user'

  return (
    <div style={isUser ? styles.userBubble : styles.botBubble}>

      {isUser ? (
        // User bubbles: plain text
        <p style={{ margin: 0, lineHeight: 1.55, whiteSpace: 'pre-wrap' }}>{message.text}</p>
      ) : (
        // Assistant bubbles: full markdown + streaming cursor
        <div style={styles.markdownBody}>
          <ReactMarkdown remarkPlugins={[remarkGfm]} components={mdComponents}>
            {message.text + (message.streaming ? '▋' : '')}
          </ReactMarkdown>
        </div>
      )}

      {message.meta?.startPage !== undefined && (message.meta.startPage ?? 0) > 0 && (
        <small style={styles.meta}>
          📄 Pages {message.meta.startPage}–{message.meta.endPage}
          {message.meta.reason ? ` · ${message.meta.reason}` : ''}
          {message.isHintOnly && ' · 💡 Hint only'}
        </small>
      )}

      {!isUser && !message.streaming && message.followUpSuggestions && message.followUpSuggestions.length > 0 && (
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
    alignSelf: 'flex-end',
    background: '#1a1a2e', color: '#fff',
    borderRadius: '18px 18px 4px 18px',
    padding: '10px 16px', maxWidth: '78%',
    fontSize: 15, lineHeight: 1.55,
    boxShadow: '0 1px 4px rgba(0,0,0,0.15)',
  },
  botBubble: {
    alignSelf: 'flex-start',
    background: '#f8fafc', color: '#1a1a2e',
    borderRadius: '4px 18px 18px 18px',
    padding: '12px 16px', maxWidth: '88%',
    fontSize: 15, lineHeight: 1.65,
    border: '1px solid #e2e8f0',
    boxShadow: '0 1px 4px rgba(0,0,0,0.06)',
  },
  markdownBody: {
    /* reset any inherited margins from first/last child */
  },
  meta:  { display: 'block', marginTop: 8, color: '#94a3b8', fontSize: 12 },
  chips: { display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 12 },
  chip:  {
    background: '#ede9fe', color: '#5b21b6',
    border: 'none', borderRadius: 20,
    padding: '5px 14px', fontSize: 12,
    cursor: 'pointer', fontWeight: 600,
    transition: 'background 0.15s',
  },
}
