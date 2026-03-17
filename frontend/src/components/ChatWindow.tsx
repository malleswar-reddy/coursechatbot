'use client'

import { useState, useRef, useEffect } from 'react'

interface Message {
  role: 'user' | 'assistant'
  text: string
  meta?: { startPage?: number; endPage?: number; reason?: string }
}

interface ChatApiResponse {
  answer: string
  startPage: number
  endPage: number
  sectionReason: string
  courseId: string
}

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'

export default function ChatWindow() {
  const [courseId, setCourseId] = useState('course1')
  const [courseIds, setCourseIds] = useState<string[]>([])
      // Fetch course IDs on mount
      useEffect(() => {
        async function fetchCourseIds() {
          try {
            const res = await fetch(`${API_URL}/api/courses/courseIds`)
            if (!res.ok) throw new Error('Failed to fetch course IDs')
            const ids: string[] = await res.json()
            setCourseIds(ids)
            if (ids.length > 0) setCourseId(ids[0])
          } catch (e) {
            // Optionally handle error
          }
        }
        fetchCourseIds()
      }, [])
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  async function sendMessage() {
    const question = input.trim()
    if (!question || loading) return

    setInput('')
    setError('')
    setMessages(prev => [...prev, { role: 'user', text: question }])
    setLoading(true)

    try {
      const res = await fetch(`${API_URL}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ courseId, question }),
      })

      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }))
        throw new Error(err.error ?? 'Request failed')
      }

      const data: ChatApiResponse = await res.json()
      setMessages(prev => [
        ...prev,
        {
          role: 'assistant',
          text: data.answer,
          meta: { startPage: data.startPage, endPage: data.endPage, reason: data.sectionReason },
        },
      ])
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Unknown error'
      setError(msg)
      setMessages(prev => [...prev, { role: 'assistant', text: `⚠️ Error: ${msg}` }])
    } finally {
      setLoading(false)
    }
  }

  function handleKey(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage()
    }
  }

  return (
    <div style={styles.container}>
      {/* Course selector */}
      <div style={styles.courseBar}>
        <label style={{ fontWeight: 600, marginRight: 8 }}>Course ID:</label>
        <select
          value={courseId}
          onChange={e => setCourseId(e.target.value)}
          style={styles.courseInput}
        >
          {courseIds.length === 0 ? (
            <option value="">Loading...</option>
          ) : (
            courseIds.map(id => (
              <option key={id} value={id}>{id}</option>
            ))
          )}
        </select>
      </div>

      {/* Messages */}
      <div style={styles.messages}>
        {messages.length === 0 && (
          <p style={styles.placeholder}>
            Ask a question about your course — e.g. "What is polymorphism?"
          </p>
        )}
        {messages.map((msg, idx) => (
          <div key={idx} style={msg.role === 'user' ? styles.userBubble : styles.botBubble}>
            <p style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{msg.text}</p>
            {msg.meta?.startPage !== undefined && (
              <small style={styles.meta}>
                📄 Pages {msg.meta.startPage}–{msg.meta.endPage}
                {msg.meta.reason ? ` · ${msg.meta.reason}` : ''}
              </small>
            )}
          </div>
        ))}
        {loading && <div style={styles.botBubble}><em>Thinking…</em></div>}
        <div ref={bottomRef} />
      </div>

      {/* Input */}
      <div style={styles.inputRow}>
        <input
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={handleKey}
          placeholder="Ask a question…"
          style={styles.textInput}
          disabled={loading}
        />
        <button onClick={sendMessage} style={styles.sendBtn} disabled={loading || !input.trim()}>
          {loading ? '…' : 'Send'}
        </button>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  container: {
    width: '100%',
    maxWidth: 720,
    background: '#fff',
    borderRadius: 12,
    boxShadow: '0 4px 24px rgba(0,0,0,0.10)',
    display: 'flex',
    flexDirection: 'column',
    overflow: 'hidden',
  },
  courseBar: {
    padding: '12px 16px',
    background: '#1a1a2e',
    color: '#fff',
    display: 'flex',
    alignItems: 'center',
  },
  courseInput: {
    padding: '4px 8px',
    borderRadius: 6,
    border: 'none',
    fontSize: 14,
    width: 140,
  },
  messages: {
    flex: 1,
    padding: '16px',
    overflowY: 'auto',
    minHeight: 400,
    maxHeight: 540,
    display: 'flex',
    flexDirection: 'column',
    gap: 12,
  },
  placeholder: {
    color: '#aaa',
    textAlign: 'center',
    marginTop: 60,
  },
  userBubble: {
    alignSelf: 'flex-end',
    background: '#1a1a2e',
    color: '#fff',
    borderRadius: '16px 16px 4px 16px',
    padding: '10px 14px',
    maxWidth: '80%',
  },
  botBubble: {
    alignSelf: 'flex-start',
    background: '#f0f0f0',
    color: '#1a1a2e',
    borderRadius: '16px 16px 16px 4px',
    padding: '10px 14px',
    maxWidth: '80%',
  },
  meta: {
    display: 'block',
    marginTop: 6,
    color: '#888',
    fontSize: 12,
  },
  inputRow: {
    display: 'flex',
    borderTop: '1px solid #eee',
    padding: '12px 16px',
    gap: 8,
  },
  textInput: {
    flex: 1,
    padding: '10px 14px',
    borderRadius: 8,
    border: '1px solid #ddd',
    fontSize: 15,
    outline: 'none',
  },
  sendBtn: {
    padding: '10px 20px',
    background: '#1a1a2e',
    color: '#fff',
    border: 'none',
    borderRadius: 8,
    cursor: 'pointer',
    fontSize: 15,
    fontWeight: 600,
  },
}
