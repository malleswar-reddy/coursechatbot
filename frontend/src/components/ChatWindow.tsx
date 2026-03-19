'use client'

import { useState, useRef, useEffect } from 'react'
import CourseGroupSelector from './CourseGroupSelector'
import DifficultySelector  from './DifficultySelector'
import ModeToggle          from './ModeToggle'
import MessageBubble       from './MessageBubble'
import PerformanceSummary  from './PerformanceSummary'
import PomodoroTimer       from './PomodoroTimer'

type Mode       = 'LEARN' | 'EXAM'
type Difficulty = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'

interface Message {
  role: 'user' | 'assistant'
  text: string
  meta?: { startPage?: number; endPage?: number; reason?: string }
  isHintOnly?: boolean
  followUpSuggestions?: string[]
}

interface ChatApiResponse {
  answer:              string
  startPage:           number
  endPage:             number
  sectionReason:       string
  courseId:            string
  sessionId:           string
  mode:                string
  difficultyLevel:     string
  isHintOnly:          boolean
  pomodoroReminder:    boolean
  followUpSuggestions: string[]
}

interface CourseItem { courseId: string; branch: string | null; subject: string | null; title: string | null }

interface PerformanceSummaryData {
  sessionId: string; questionsAttempted: number; hintCount: number
  conceptGaps: string[]; studyDurationMinutes: number
}

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'

export default function ChatWindow() {
  const [courses,      setCourses]    = useState<CourseItem[]>([])
  const [courseId,     setCourseId]   = useState('')
  const [mode,         setMode]       = useState<Mode>('LEARN')
  const [difficulty,   setDifficulty] = useState<Difficulty>('INTERMEDIATE')
  const [sessionId,    setSessionId]  = useState<string | null>(null)
  const [messages,     setMessages]   = useState<Message[]>([])
  const [input,        setInput]      = useState('')
  const [loading,      setLoading]    = useState(false)
  const [error,        setError]      = useState('')
  const [showPomodoro, setShowPomodoro] = useState(false)
  const [summary,      setSummary]    = useState<PerformanceSummaryData | null>(null)
  const bottomRef = useRef<HTMLDivElement>(null)

  // Fetch all courses (with branch metadata) on mount
  useEffect(() => {
    fetch(`${API_URL}/api/courses`)
      .then(r => r.json())
      .then((list: CourseItem[]) => {
        setCourses(list)
        if (list.length > 0) setCourseId(list[0].courseId)
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  // Create or re-use a session before the first message
  async function ensureSession(): Promise<string | null> {
    if (sessionId) return sessionId
    try {
      const res = await fetch(`${API_URL}/api/chat/session`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ courseId, mode, difficultyLevel: difficulty }),
      })
      if (!res.ok) return null
      const data = await res.json()
      setSessionId(data.sessionId)
      return data.sessionId
    } catch { return null }
  }

  async function sendMessage(overrideText?: string) {
    const question = (overrideText ?? input).trim()
    if (!question || loading) return

    setInput('')
    setError('')
    setMessages(prev => [...prev, { role: 'user', text: question }])
    setLoading(true)

    try {
      const sid = await ensureSession()

      const res = await fetch(`${API_URL}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ courseId, question, mode, difficultyLevel: difficulty, sessionId: sid }),
      })

      if (!res.ok) {
        const err = await res.json().catch(() => ({ error: res.statusText }))
        throw new Error(err.error ?? 'Request failed')
      }

      const data: ChatApiResponse = await res.json()

      if (data.pomodoroReminder) setShowPomodoro(true)

      setMessages(prev => [...prev, {
        role: 'assistant',
        text: data.answer,
        meta: { startPage: data.startPage, endPage: data.endPage, reason: data.sectionReason },
        isHintOnly: data.isHintOnly,
        followUpSuggestions: data.followUpSuggestions,
      }])
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Unknown error'
      setError(msg)
      setMessages(prev => [...prev, { role: 'assistant', text: `⚠️ Error: ${msg}` }])
    } finally {
      setLoading(false)
    }
  }

  async function endExam() {
    if (!sessionId) return
    try {
      const res = await fetch(`${API_URL}/api/chat/session/${sessionId}/summary`)
      if (res.ok) {
        const data: PerformanceSummaryData = await res.json()
        setSummary(data)
      }
    } catch {}
  }

  function handleModeChange(newMode: Mode) {
    setMode(newMode)
    setSessionId(null) // new session on mode switch
  }

  return (
    <div style={styles.container}>
      {summary && (
        <PerformanceSummary
          {...summary}
          onClose={() => setSummary(null)}
        />
      )}

      {/* Top bar */}
      <div style={styles.topBar}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <label style={styles.label}>Course:</label>
          <CourseGroupSelector courses={courses} value={courseId} onChange={(id: string) => { setCourseId(id); setSessionId(null) }} />
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <ModeToggle value={mode} onChange={handleModeChange} />
          {mode === 'EXAM' && sessionId && (
            <button onClick={endExam} style={styles.endExamBtn}>End Exam 📊</button>
          )}
        </div>
      </div>

      {/* Difficulty bar */}
      <div style={styles.diffBar}>
        <span style={styles.label}>Level:</span>
        <DifficultySelector value={difficulty} onChange={(d: Difficulty) => { setDifficulty(d); setSessionId(null) }} />
      </div>

      {/* Pomodoro */}
      {showPomodoro && (
        <div style={styles.pomodoroWrap}>
          <span style={{ fontSize: 13, color: '#b45309', fontWeight: 600, marginRight: 8 }}>
            🍅 25 min focus reached — consider a short break!
          </span>
          <PomodoroTimer />
          <button onClick={() => setShowPomodoro(false)} style={styles.dismissBtn}>✕</button>
        </div>
      )}

      {/* Messages */}
      <div style={styles.messages}>
        {messages.length === 0 && (
          <p style={styles.placeholder}>
            {mode === 'EXAM'
              ? '📝 Exam mode active — I\'ll give hints, not full answers. Ask your question!'
              : '📖 Learn mode — Ask any question about your course!'}
          </p>
        )}
        {messages.map((msg, idx) => (
          <MessageBubble key={idx} message={msg} onChipClick={text => sendMessage(text)} />
        ))}
        {loading && <div style={styles.botBubble}><em>Thinking…</em></div>}
        <div ref={bottomRef} />
      </div>

      {/* Input row */}
      <div style={styles.inputRow}>
        <input
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage() } }}
          placeholder={mode === 'EXAM' ? 'Ask for a hint…' : 'Ask a question…'}
          style={styles.textInput}
          disabled={loading}
        />
        <button onClick={() => sendMessage()} style={styles.sendBtn} disabled={loading || !input.trim()}>
          {loading ? '…' : 'Send'}
        </button>
      </div>
    </div>
  )
}

const styles: Record<string, React.CSSProperties> = {
  container: {
    width: '100%', maxWidth: 760, background: '#fff',
    borderRadius: 12, boxShadow: '0 4px 24px rgba(0,0,0,0.10)',
    display: 'flex', flexDirection: 'column', overflow: 'hidden',
  },
  topBar: {
    padding: '10px 16px', background: '#1a1a2e', color: '#fff',
    display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 8,
  },
  diffBar: {
    padding: '8px 16px', background: '#16213e', color: '#fff',
    display: 'flex', alignItems: 'center', gap: 10,
  },
  label:      { fontWeight: 600, fontSize: 13, color: '#cbd5e1' },
  endExamBtn: {
    padding: '5px 12px', background: '#ef4444', color: '#fff',
    border: 'none', borderRadius: 6, cursor: 'pointer', fontSize: 13, fontWeight: 600,
  },
  pomodoroWrap: {
    display: 'flex', alignItems: 'center', background: '#fef9c3',
    padding: '4px 12px', borderBottom: '1px solid #fde047',
  },
  dismissBtn: { marginLeft: 'auto', background: 'none', border: 'none', cursor: 'pointer', fontSize: 14, color: '#92400e' },
  messages:   { flex: 1, padding: '16px', overflowY: 'auto', minHeight: 400, maxHeight: 540, display: 'flex', flexDirection: 'column', gap: 12 },
  placeholder:{ color: '#aaa', textAlign: 'center', marginTop: 60 },
  botBubble:  { alignSelf: 'flex-start', background: '#f0f0f0', color: '#1a1a2e', borderRadius: '16px 16px 16px 4px', padding: '10px 14px', maxWidth: '80%' },
  inputRow:   { display: 'flex', borderTop: '1px solid #eee', padding: '12px 16px', gap: 8 },
  textInput:  { flex: 1, padding: '10px 14px', borderRadius: 8, border: '1px solid #ddd', fontSize: 15, outline: 'none' },
  sendBtn:    { padding: '10px 20px', background: '#1a1a2e', color: '#fff', border: 'none', borderRadius: 8, cursor: 'pointer', fontSize: 15, fontWeight: 600 },
}
